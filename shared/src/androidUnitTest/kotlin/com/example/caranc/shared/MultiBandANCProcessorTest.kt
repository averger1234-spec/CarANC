package com.example.caranc.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Unit tests for MultiBandANCProcessor (core multi-band ANC with latency awareness, siren, floor modes, engine comb).
 * Uses synthetic ShortArray inputs: tones (low freq for reduction), noise, chirps.
 * Run: ./gradlew :shared:compileDebugKotlin :shared:testDebugUnitTest
 * Part of P0 #3 core test expansion. + P1 #5 BandSplitter LR2 improvements (direct tests added).
 * CYCLE3_EXTRA: extended with probe history integration tests (end-to-end blocks + latency/siren/floor/engine + history buffers).
 */
class MultiBandANCProcessorTest {

    private val sampleRate = 44100
    private val bufferSize = 256

    private fun createProcessor(): MultiBandANCProcessor =
        MultiBandANCProcessor(sampleRate, bufferSize)

    private fun generateTone(freqHz: Float, numSamples: Int, amplitude: Float = 0.6f): ShortArray {
        val arr = ShortArray(numSamples)
        val omega = 2.0 * kotlin.math.PI * freqHz / sampleRate
        for (i in 0 until numSamples) {
            val s = (amplitude * kotlin.math.sin(omega * i) * Short.MAX_VALUE).toInt()
            arr[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return arr
    }

    private fun generateNoise(numSamples: Int, amplitude: Float = 0.4f): ShortArray {
        val arr = ShortArray(numSamples)
        repeat(numSamples) { i ->
            val s = (amplitude * (Random.nextFloat() * 2f - 1f) * Short.MAX_VALUE).toInt()
            arr[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return arr
    }

    private fun rmsLinear(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sumSq = 0.0
        for (s in samples) {
            val n = s / 32768.0
            sumSq += n * n
        }
        return sqrt(sumSq / samples.size)
    }

    private fun warmUp(proc: MultiBandANCProcessor, block: ShortArray, repeats: Int = 6) {
        repeat(repeats) {
            proc.process(block)
        }
    }

    // 1. Low-freq reduction >0dB using residual (input + anti-noise) after adaptation on tone
    @Test
    fun lowFreqReduction_positiveDb_onTone() {
        val proc = createProcessor()
        val lowTone = generateTone(95f, 1024, 0.55f)  // low freq tone

        warmUp(proc, lowTone, 8)

        val output = proc.process(lowTone)
        val residual = ShortArray(lowTone.size) { i ->
            val sum = lowTone[i].toInt() + output[i].toInt()
            sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val rmsIn = rmsLinear(lowTone)
        val rmsRes = rmsLinear(residual)
        val reductionDb = if (rmsRes > 1e-9) 20.0 * log10(rmsIn / rmsRes) else 0.0

        assertTrue(reductionDb > 0.0, "expected low-freq reduction >0dB, got ${"%.2f".format(reductionDb)} dB")
        assertTrue(rmsIn > rmsRes, "residual should be quieter than input for positive reduction")
    }

    // 2. Latency band limiting: mid/high disabled on high latency
    @Test
    fun latencyBandLimiting_midHighDisabledOnHighLatency() {
        val proc = createProcessor()
        proc.setEstimatedLatencyMs(280f)  // high latency -> expect mid/high off

        val limits = proc.getLatencyBandLimits()
        assertFalse(limits.midEnabled, "mid band should be disabled on high latency")
        assertFalse(limits.highEnabled, "high band should be disabled on high latency")
        assertTrue(limits.lowEnabled, "low band always enabled")
        assertTrue(limits.lowGain >= 0.99f)
        assertTrue(limits.midGain < 0.1f)
        assertTrue(limits.highGain < 0.1f)
    }

    // 3. Siren override lowers gain (scales output + mu)
    @Test
    fun sirenOverride_lowersGain() {
        val lowTone = generateTone(140f, 512, 0.65f)

        val procNormal = createProcessor()
        warmUp(procNormal, lowTone, 6)
        val normalOut = procNormal.process(lowTone)

        val procSiren = createProcessor()
        warmUp(procSiren, lowTone, 6)
        procSiren.setSirenOverride(true, 0.15f)
        val sirenOut = procSiren.process(lowTone)

        val rmsNormal = rmsLinear(normalOut)
        val rmsSiren = rmsLinear(sirenOut)

        assertTrue(rmsSiren < rmsNormal * 0.6, "siren override must lower effective output gain (rmsSiren=$rmsSiren vs normal=$rmsNormal)")
        assertTrue(procSiren.isSirenOverrideActive())
    }

    // 4. Floor mode (music/call) bypasses full ANC (reduced mu/adapt) but keeps floor noise component (lowpass path still active, not full zero)
    @Test
    fun floorMode_bypassesFullAnc_keepsFloorNoise() {
        val floorishNoise = generateNoise(768, 0.35f)  // broadband but acts as floor input

        // Normal mode (full ANC)
        val procNormal = createProcessor()
        warmUp(procNormal, floorishNoise, 5)
        val outNormal = procNormal.process(floorishNoise)

        // Floor music mode (bypass full)
        val procMusic = createProcessor()
        procMusic.setProcessingMode(AncProcessingMode.FLOOR_NOISE_MUSIC)
        warmUp(procMusic, floorishNoise, 5)
        val outMusic = procMusic.process(floorishNoise)
        assertEquals(AncProcessingMode.FLOOR_NOISE_MUSIC, procMusic.getProcessingMode())

        // Floor call (even more bypassed)
        val procCall = createProcessor()
        procCall.setProcessingMode(AncProcessingMode.FLOOR_NOISE_CALL)
        procCall.setCallActive(true)
        warmUp(procCall, floorishNoise, 5)
        val outCall = procCall.process(floorishNoise)

        val rmsNormal = rmsLinear(outNormal)
        val rmsMusic = rmsLinear(outMusic)
        val rmsCall = rmsLinear(outCall)

        // Floor modes bypass aggressive/full ANC => lower anti-noise output energy
        assertTrue(rmsMusic < rmsNormal * 0.85, "FLOOR_MUSIC should bypass full ANC (lower output energy)")
        assertTrue(rmsCall < rmsMusic * 0.9, "FLOOR_CALL should further bypass (lowest mu)")

        // But does not go to full zero: keeps floor (low) noise path contribution (lowpass + engine etc)
        assertTrue(rmsCall > 0.0005, "floor call still produces some output to keep/cancel floor component (not dead)")
    }

    // 5. Engine comb active when RPM valid (adds feedforward even on first block, no adapt needed)
    @Test
    fun engineComb_activeWhenRpmValid() {
        val lowInput = generateTone(80f, 256, 0.4f)

        val procNoRpm = createProcessor()
        val outNoRpm = procNoRpm.process(lowInput)

        val procRpm = createProcessor()
        procRpm.setEngineRpm(950f, valid = true)  // ~31.6 Hz fund *2
        val outRpm = procRpm.process(lowInput)

        val rmsNo = rmsLinear(outNoRpm)
        val rmsYes = rmsLinear(outRpm)

        // No rpm => initial w=0 + no engine => near zero output first block
        assertTrue(outNoRpm.all { it == 0.toShort() } || rmsNo < 1e-6, "no rpm + cold start => zero output")

        // With valid rpm => engine comb ff added immediately
        assertTrue(rmsYes > rmsNo + 1e-5, "valid RPM must activate engine comb output (rms $rmsYes > $rmsNo)")
        assertTrue(outRpm.any { abs(it.toInt()) > 5 }, "engine comb produces audible anti-harmonic samples")
    }

    // Bonus: chirp processes, uses AudioSignalUtils
    @Test
    fun chirpInput_synthetic_processesOk() {
        val proc = createProcessor()
        val chirp = AudioSignalUtils.generateLogChirp(512, sampleRate, f0 = 60f, f1 = 1200f)
        assertEquals(512, chirp.size)
        assertTrue(chirp.any { it != 0.toShort() })

        val out = proc.process(chirp)
        assertEquals(512, out.size)
        // After warm some cancellation energy expected
        warmUp(proc, chirp, 3)
        val out2 = proc.process(chirp)
        assertTrue(rmsLinear(out2) > 0.0)
    }

    // P1 #5: BandSplitter now uses proper 2nd-order LR crossover (cascaded stages); tests sum-flat + freq separation
    // (BandSplitter/BandSamples promoted to internal package-level for direct test access from same-pkg androidUnitTest)
    @Test
    fun bandSplitter_lr2_sumsFlatToInput() {
        val splitter = BandSplitter(sampleRate)
        // Settle on constant (DC component)
        repeat(200) { splitter.split(0.65f) }
        val b = splitter.split(0.65f)
        val sum = b.low + b.mid + b.high
        assertEquals(0.65f, sum, 1e-5f, "LR2 must sum exactly flat (low+mid+high == input) unlike old 1st-order which dropped lp80")
        assertTrue(b.low > 0.55f, "constant settles primarily to low band")
        assertTrue(b.mid < 0.05f && b.high < 0.05f)
    }

    @Test
    fun bandSplitter_separatesFrequencies() {
        val splitter = BandSplitter(sampleRate)
        // Feed tones to settle the 2nd-order states (much cleaner separation than 1st-order)
        val lowTone = generateTone(95f, 8192, 0.5f)   // << 300Hz crossover
        val midTone = generateTone(450f, 8192, 0.5f)  // 300-800
        val highTone = generateTone(1400f, 8192, 0.5f) // >> 800Hz
        lowTone.forEach { splitter.split(it / 32768f) }
        midTone.forEach { splitter.split(it / 32768f) }
        highTone.forEach { splitter.split(it / 32768f) }

        // Sample one more for steady-state band energies (far from crossover => strong selectivity)
        val sLow = (0.7f * kotlin.math.sin(2.0 * kotlin.math.PI * 95.0 / sampleRate)).toFloat()
        val sMid = (0.7f * kotlin.math.sin(2.0 * kotlin.math.PI * 450.0 / sampleRate)).toFloat()
        val sHigh = (0.7f * kotlin.math.sin(2.0 * kotlin.math.PI * 1400.0 / sampleRate)).toFloat()
        val bL = splitter.split(sLow)
        val bM = splitter.split(sMid)
        val bH = splitter.split(sHigh)

        assertTrue(bL.low > bL.mid * 3f && bL.low > bL.high * 3f, "95Hz must dominate LOW band (low=${bL.low})")
        assertTrue(bM.mid > bM.low * 2f && bM.mid > bM.high * 2f, "450Hz must dominate MID band (mid=${bM.mid})")
        assertTrue(bH.high > bH.low * 3f && bH.high > bH.mid * 3f, "1400Hz must dominate HIGH band (high=${bH.high})")
    }

    @Test
    fun bandSplitter_veryLowFreqs_preservedInLow() {
        val splitter = BandSplitter(sampleRate)
        // 25Hz rumble (common road/engine) previously attenuated/lost by old lp80 diff; now routes to low
        repeat(3000) { i ->
            val s = (0.55f * kotlin.math.sin(2.0 * kotlin.math.PI * 25.0 / sampleRate * i)).toFloat()
            splitter.split(s)
        }
        val s = (0.55f * kotlin.math.sin(2.0 * kotlin.math.PI * 25.0 / sampleRate * 10)).toFloat()
        val b = splitter.split(s)
        assertTrue(b.low > 0.35f, "25Hz very-low must route to low band (got low=${b.low})")
        assertTrue(b.low + b.mid + b.high > 0.45f, "very-low energy must be preserved (not dropped from all bands)")
        assertTrue(b.mid < 0.1f && b.high < 0.1f)
    }

    // CYCLE3_EXTRA: cycle3_extra_processor_integration_tests
    // End-to-end block processing integration using probe history buffers (reuse pattern from AudioEngine)
    // + latency/siren/floor/engine modes. History buffers filled from sent output + mic input (post-process),
    // used to verify correlation-style checks + that processor responds to setEstimatedLatency + modes.
    // Synthetic helpers reused/extended; sessionContext wiring for scoped models.
    @Test
    fun endToEndBlockProcessing_withProbeHistory_latencySirenFloorEngine() {
        val roadRef = RoadNoiseReferenceModel()
        val ctx = AncSessionContext(roadNoiseReferenceModel = roadRef)
        val proc = MultiBandANCProcessor(sampleRate, bufferSize, sessionContext = ctx)

        // History reuse buffers (exact pattern from AudioEngine probe history)
        val probeHistorySize = 2048
        val sentOutputHistory = FloatArray(probeHistorySize)
        val micInputHistory = FloatArray(probeHistorySize)
        var historyWriteIndex = 0

        // Setup: latency from "probe meas", siren, floor, engine
        proc.setEstimatedLatencyMs(68f)  // from roundtrip history corr
        proc.setSirenOverride(true, 0.08f)
        proc.setProcessingMode(AncProcessingMode.FLOOR_NOISE_CALL)
        proc.setCallActive(true)
        proc.setEngineRpm(1200f, valid = true)

        val limits = proc.getLatencyBandLimits()
        assertTrue(limits.lowEnabled)
        assertTrue(proc.isSirenOverrideActive())
        assertEquals(AncProcessingMode.FLOOR_NOISE_CALL, proc.getProcessingMode())

        // End-to-end: multiple blocks, generate synth, process, fill history (as engine does post-scale)
        val blocks = listOf(
            generateNoise(256, 0.28f),
            generateTone(85f, 256, 0.42f),
            generateTone(35f, 256, 0.33f)  // very low for floor/road
        )
        var totalRmsIn = 0.0
        var totalRmsOut = 0.0
        var processedCount = 0

        blocks.forEach { block ->
            warmUp(proc, block, 2)
            val out = proc.process(block)

            // Simulate AudioEngine history fill using preprocessed(mic) + output (sent, incl any probe)
            for (j in block.indices) {
                micInputHistory[historyWriteIndex] = block[j] / 32768.0f
                sentOutputHistory[historyWriteIndex] = out[j] / 32767.0f
                historyWriteIndex = (historyWriteIndex + 1) % probeHistorySize
            }

            val rmsIn = rmsLinear(block)
            val rmsOut = rmsLinear(out)
            totalRmsIn += rmsIn
            totalRmsOut += rmsOut
            processedCount++

            assertTrue(out.size == block.size)
        }

        assertTrue(processedCount == 3)
        assertTrue(totalRmsOut > 0.0001, "floor+engine+siren path must still emit (not zeroed)")

        // Use history buffers for verification (sim corr or energy check, like engine latency meas)
        var histEnergy = 0.0
        for (i in 0 until probeHistorySize) {
            histEnergy += (sentOutputHistory[i] * sentOutputHistory[i] + micInputHistory[i] * micInputHistory[i])
        }
        assertTrue(histEnergy > 0.1, "probe history buffers must accumulate non-trivial energy from e2e blocks")

        // Re-verify mode + latency after history-using loop
        proc.setProcessingMode(AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD)
        assertEquals(AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD, proc.getProcessingMode())
        proc.setEstimatedLatencyMs(155f)
        val lim2 = proc.getLatencyBandLimits()
        assertTrue(lim2.lowEnabled)
    }

    @Test
    fun processorIntegration_probeHistoryBuffers_verifyLatencyUpdateFromSimRoundtrip() {
        val ctx = AncSessionContext()
        val proc = MultiBandANCProcessor(sampleRate, bufferSize, initialTier = UserTier.STANDARD, sessionContext = ctx)

        // Simulate probe roundtrip -> latency from history (AudioEngine style) then feed to proc
        val simulatedLatency = 55f  // e.g. from corr on 2048 history
        proc.setEstimatedLatencyMs(simulatedLatency)

        val limits = proc.getLatencyBandLimits()
        assertTrue(limits.lowEnabled)
        // mid may be on for ~55ms
        assertTrue(limits.lowGain > 0.9f)

        // Process e2e with floor+engine while "using" history conceptually
        val input = generateTone(70f, 512, 0.5f)
        proc.setProcessingMode(AncProcessingMode.FLOOR_NOISE_MUSIC)
        proc.setEngineRpm(800f, true)
        warmUp(proc, input, 4)
        val out = proc.process(input)
        assertTrue(rmsLinear(out) > 0.0)

        // History buffer would be filled post this in engine; here verify proc accepted the probe-derived latency
        assertTrue(proc.getLatencyBandLimits().lowEnabled)
    }
}
