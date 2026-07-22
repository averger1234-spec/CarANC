package com.example.caranc.shared.latency

import androidx.annotation.Keep
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Closed-loop residual check for ANC (literature-correct self-validation):
 *
 *   residual_plant[n] = primary[n] + plant(anti)[n]
 *   plant ≈ pure delay D (+ optional short ŝ FIR)
 *
 * App anti y is mixed only after plant delay D (secondary path), matching FxLMS filtered-x.
 * Same-index residual = primary+anti is **wrong** for large AA D and overstates cancel or noise.
 *
 * Used by:
 * - Unit closed-loop tests (synthetic road noise → process → plant mix → assert red>0)
 * - AudioEngine running_snapshot band metrics (program-side KPI, not external mic recording)
 */
@Keep
object PlantAlignedResidual {

    /** ShortArray residual: primary[i] + anti[i - delaySamples] (0 if i < delay). */
    fun mix(
        primary: ShortArray,
        anti: ShortArray,
        delaySamples: Int
    ): ShortArray {
        val n = primary.size.coerceAtMost(anti.size)
        val d = delaySamples.coerceAtLeast(0)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val a = if (i >= d) anti[i - d].toInt() else 0
            val s = (primary[i].toInt() + a).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = s.toShort()
        }
        return out
    }

    fun rmsDb(samples: ShortArray, start: Int = 0, endExclusive: Int = samples.size): Float {
        val end = endExclusive.coerceIn(0, samples.size)
        val st = start.coerceIn(0, end)
        val len = end - st
        if (len <= 0) return -90f
        var sumSq = 0.0
        for (i in st until end) {
            val n = samples[i] / 32768.0
            sumSq += n * n
        }
        val rms = sqrt(sumSq / len)
        return (20.0 * ln(rms + 1e-12) / ln(10.0)).toFloat()
    }

    fun reductionDb(primaryRmsDb: Float, residualRmsDb: Float): Float {
        // positive => residual quieter than primary
        return (primaryRmsDb - residualRmsDb)
    }

    /**
     * Band-limited energy in linear magnitude terms using Goertzel-like single-bin energy
     * over fixed frequencies (cheap, no full FFT required for KPI).
     */
    fun bandEnergyDb(
        samples: ShortArray,
        sampleRate: Int,
        freqHz: Float,
        start: Int = 0,
        endExclusive: Int = samples.size
    ): Float {
        val end = endExclusive.coerceIn(0, samples.size)
        val st = start.coerceIn(0, end)
        val len = end - st
        if (len < 8 || sampleRate <= 0) return -90f
        val w = 2.0 * Math.PI * freqHz / sampleRate
        var re = 0.0
        var im = 0.0
        for (i in 0 until len) {
            val x = samples[st + i] / 32768.0
            re += x * kotlin.math.cos(w * i)
            im += x * kotlin.math.sin(w * i)
        }
        val mag = sqrt(re * re + im * im) / len
        return (20.0 * log10(mag + 1e-12)).toFloat()
    }

    data class ClosedLoopReport(
        val primaryRmsDb: Float,
        val residualSameIdxRmsDb: Float,
        val residualPlantRmsDb: Float,
        val plantReductionDb: Float,
        val sameIdxReductionDb: Float,
        val antiRmsDb: Float,
        val delaySamples: Int
    )

    /**
     * Full closed-loop report for one block of primary + anti with plant delay D.
     * Skip first [delaySamples] when measuring plant residual RMS (fill time).
     */
    fun report(primary: ShortArray, anti: ShortArray, delaySamples: Int): ClosedLoopReport {
        val d = delaySamples.coerceAtLeast(0)
        val plantRes = mix(primary, anti, d)
        val sameRes = mix(primary, anti, 0)
        val skip = d.coerceAtMost(primary.size / 2)
        val pDb = rmsDb(primary, skip, primary.size)
        val plantDb = rmsDb(plantRes, skip, plantRes.size)
        val sameDb = rmsDb(sameRes, skip, sameRes.size)
        val antiDb = rmsDb(anti, skip, anti.size)
        return ClosedLoopReport(
            primaryRmsDb = pDb,
            residualSameIdxRmsDb = sameDb,
            residualPlantRmsDb = plantDb,
            plantReductionDb = reductionDb(pDb, plantDb),
            sameIdxReductionDb = reductionDb(pDb, sameDb),
            antiRmsDb = antiDb,
            delaySamples = d
        )
    }
}

/**
 * Ring of recent anti samples for live plant-aligned residual metrics in AudioEngine.
 */
@Keep
class AntiNoiseDelayLine(capacity: Int = 16384) {
    private val buf = ShortArray(capacity.coerceAtLeast(256))
    private var write = 0
    private var count = 0

    fun push(block: ShortArray, size: Int) {
        val n = size.coerceAtMost(block.size)
        for (i in 0 until n) {
            buf[write % buf.size] = block[i]
            write++
            if (count < buf.size) count++
        }
    }

    /** Sample delayed by [delay] (0 = latest). */
    fun delayed(delay: Int): Short {
        if (count == 0) return 0
        val d = delay.coerceIn(0, count - 1)
        val idx = (write - 1 - d).mod(buf.size)
        return buf[idx]
    }

    fun fillPlantMixed(primary: ShortArray, size: Int, delaySamples: Int, dest: ShortArray) {
        val n = size.coerceAtMost(primary.size).coerceAtMost(dest.size)
        val d = delaySamples.coerceAtLeast(0)
        for (i in 0 until n) {
            // Primary is current mic; anti delayed by plant D relative to *end of this block*
            // Approximate: for sample i in block, delay from latest is (n-1-i)+D
            val lag = (n - 1 - i) + d
            val a = delayed(lag).toInt()
            dest[i] = (primary[i].toInt() + a)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }
}
