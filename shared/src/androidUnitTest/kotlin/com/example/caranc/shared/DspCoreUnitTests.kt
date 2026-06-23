package com.example.caranc.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.math.abs

/**
 * Basic unit tests for core DSP components (FftUtils, SpectrumAnalyzer, AudioSignalUtils, AncLatencyEstimator).
 * These are pure functions and fully testable on JVM without Android runtime.
 * Run: ./gradlew :shared:compileDebugKotlin :shared:testDebugUnitTest
 * Part of P1 test sourceSet setup.
 */
class DspCoreUnitTests {

    // 1. Test FftUtils.nextPowerOfTwo + roundtrip sanity (forward/inverse)
    @Test
    fun fftUtils_nextPowerOfTwo_andRoundtrip() {
        assertEquals(2, FftUtils.nextPowerOfTwo(0))
        assertEquals(2, FftUtils.nextPowerOfTwo(1))
        assertEquals(2, FftUtils.nextPowerOfTwo(2))
        assertEquals(4, FftUtils.nextPowerOfTwo(3))
        assertEquals(4, FftUtils.nextPowerOfTwo(4))
        assertEquals(8, FftUtils.nextPowerOfTwo(5))
        assertEquals(8, FftUtils.nextPowerOfTwo(7))
        assertEquals(16, FftUtils.nextPowerOfTwo(9))
        assertEquals(1024, FftUtils.nextPowerOfTwo(1000))

        // Roundtrip sanity: small power-of-2 complex data
        val n = 8
        val data = FloatArray(n * 2) { i ->
            if (i % 2 == 0) (i / 2).toFloat() else 0f  // simple real values
        }
        val original = data.copyOf()
        FftUtils.complexForward(data)
        FftUtils.complexInverse(data, scale = true)
        for (i in original.indices) {
            assertTrue(abs(original[i] - data[i]) < 1e-3f, "roundtrip mismatch at $i: ${original[i]} vs ${data[i]}")
        }
    }

    // 2. Test SpectrumAnalyzer basic magnitude spectrum
    @Test
    fun spectrumAnalyzer_basicMagnitude() {
        val samples = ShortArray(512) { (it % 100 - 50).toShort() }  // simple tone-like
        // CYCLE3_EXTRA: SpectrumAnalyzer now class (not object); use instance() for direct test of DSP core.
        // (In prod, prefer via sessionContext.spectrumAnalyzer for scoping.)
        val mag64 = SpectrumAnalyzer().computeMagnitudeSpectrum(samples, binCount = 64)
        assertEquals(64, mag64.size)
        assertTrue(mag64.any { it > 0f }, "expected some non-zero magnitude bins")

        val mag32 = SpectrumAnalyzer().computeMagnitudeSpectrum(samples, binCount = 32)
        assertEquals(32, mag32.size)

        val rms = SpectrumAnalyzer().computeRmsDb(samples)
        assertTrue(rms < 0f, "rmsDb for low amp should be negative")
        assertTrue(rms > -100f)

        val empty = SpectrumAnalyzer().computeMagnitudeSpectrum(ShortArray(0))
        assertEquals(64, empty.size)
    }

    // 3. Test AudioSignalUtils chirp length
    @Test
    fun audioSignalUtils_chirpLength() {
        val sr = 44100
        val chirp256 = AudioSignalUtils.generateLogChirp(256, sr)
        assertEquals(256, chirp256.size)
        assertTrue(chirp256.any { it != 0.toShort() })

        val chirp1024 = AudioSignalUtils.generateLogChirp(1024, sr, f0 = 50f, f1 = 8000f)
        assertEquals(1024, chirp1024.size)

        // Default amp range sanity
        val maxAmp = chirp1024.maxOf { abs(it.toInt()) }
        assertTrue(maxAmp > 100, "chirp should have audible amplitude")
        assertTrue(maxAmp <= Short.MAX_VALUE)
    }

    // 4. Test AncLatencyEstimator math (both overloads + maxCancel)
    @Test
    fun ancLatencyEstimator_math() {
        val bd = AncLatencyEstimator.estimate(
            sampleRate = 44100,
            recordBufferSamples = 128,
            trackBufferSamples = 128,
            readSize = 64,
            acousticDelaySamples = 200,
            frameworkMarginMs = 35f
        )
        assertNotNull(bd)
        assertTrue(bd.totalMs > 0f)
        assertTrue(bd.recordBufferMs > 0f)
        assertTrue(bd.acousticDelayMs > 0f)
        assertEquals(bd.totalMs, bd.recordBufferMs + bd.trackBufferMs + bd.processingBlockMs + bd.acousticDelayMs + bd.frameworkMarginMs)

        // Second overload (hal)
        val bd2 = AncLatencyEstimator.estimate(44100, 256, 64, 120)
        assertTrue(bd2.totalMs > 10f)

        val fmax = AncLatencyEstimator.maxCancelFrequencyHz(40f)
        assertTrue(fmax in 35f..350f)

        val fmaxEdge = AncLatencyEstimator.maxCancelFrequencyHz(5f) // clamped
        assertTrue(fmaxEdge >= 35f)
    }

    // Bonus 5th: simple sanity on another pure util (average energy)
    @Test
    fun audioSignalUtils_averageEnergy_basic() {
        val sig = ShortArray(100) { 1000 }
        val energy = AudioSignalUtils.averageAbsoluteEnergy(sig)
        assertTrue(energy > 0.0)
        assertEquals(1000.0, energy, 1.0)  // approx since abs
    }
}
