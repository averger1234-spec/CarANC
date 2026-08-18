package com.example.caranc.shared.latency

import com.example.caranc.shared.Keep
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **Cabin boom pressure path** — put real low-frequency anti energy on the speakers for 悶.
 *
 * 1.2.11: delay must match **total AA RTT** (not track+framework alone); no soft-boost
 * synthetic samples (they were uncorrelated LF that made cabin louder); optional polarity.
 *
 * Mechanism:
 * - Ring-buffer cabin mic low-band sample
 * - Read x(n − D) where D ≈ total measured path delay (record+track+framework…)
 * - Output anti = polarity · (−gain · lowpass(x(n−D)))
 * - Gain scales with speed + |low| energy; floor so cruise always has pressure
 */
@Keep
class CabinBoomPressure(
    private val sampleRate: Int,
    capacity: Int = 16384
) {
    private val ring = FloatArray(capacity.coerceAtLeast(2048))
    private var write = 0
    private var count = 0

    // Low LPF (~85 Hz) — boom band, kill electronic mid/high
    private var lp1 = 0f
    private var lp2 = 0f
    private val lpCoeff: Float =
        (2.0 * kotlin.math.PI * 85.0 / sampleRate).toFloat().coerceIn(0.006f, 0.10f)

    var lastOutput = 0f
        private set
    var lastGain = 0f
        private set
    /** Block RMS of |y| for KPI (log). */
    var lastRms = 0f
        private set
    /** Block peak of |y| for KPI (log). */
    var lastPeak = 0f
        private set
    /** Last delay actually used (samples). */
    var lastDelayUsed = 0
        private set

    private var sumSq = 0.0
    private var peakAbs = 0f
    private var blockN = 0

    @Keep
    fun push(micLowSample: Float) {
        ring[write % ring.size] = micLowSample.coerceIn(-1.5f, 1.5f)
        write++
        if (count < ring.size) count++
    }

    /**
     * @param plantDelaySamples total path delay at process sample rate (prefer measured RTT)
     * @param speedKmh vehicle speed
     * @param boomPriority ROAD / rumble focus
     * @param polarity +1 or −1 (auto-flip from lagged corr when phase is inverted)
     * @return speaker anti sample (already − polarity × LPF, then × polarity latch)
     */
    @Keep
    fun process(
        plantDelaySamples: Int,
        speedKmh: Float,
        boomPriority: Boolean,
        freeze: Boolean,
        polarity: Float = 1f
    ): Float {
        if (!boomPriority || speedKmh < 16f || count < 8) {
            lastOutput *= 0.92f
            lastGain = 0f
            accumulate(lastOutput)
            return lastOutput
        }
        val d = plantDelaySamples.coerceIn(0, min(count - 1, ring.size - 1))
        // Prefer usable delay: if plant tiny, ~25 ms acoustic floor (not full AA)
        val dUse = if (d < sampleRate / 80) (sampleRate / 40).coerceAtMost(count - 1) else d
        lastDelayUsed = dUse
        val idx = (write - 1 - dUse).mod(ring.size)
        val delayed = ring[idx]

        val speedNorm = ((speedKmh - 12f) / 65f).coerceIn(0.20f, 1.35f)
        val energyFloor = when {
            speedKmh >= 50f -> 0.12f
            speedKmh >= 35f -> 0.08f
            else -> 0.04f
        }
        val energy = abs(delayed).coerceIn(energyFloor, 0.90f)
        val gain = (0.85f + 0.70f * speedNorm) * (0.55f + 1.35f * energy)
            .coerceIn(0.55f, 1.75f)
        lastGain = if (freeze) lastGain * 0.99f else gain

        // 1.2.11: no soft-boost invention — only real delayed mic (was +cabin energy)
        var y = -delayed * lastGain
        val pol = if (polarity >= 0f) 1f else -1f
        y *= pol
        // Dual 1-pole ~85 Hz
        lp1 += lpCoeff * (y - lp1)
        lp2 += lpCoeff * (lp1 - lp2)
        y = lp2.coerceIn(-0.95f, 0.95f)
        lastOutput = y
        accumulate(y)
        return y
    }

    private fun accumulate(y: Float) {
        val a = abs(y)
        sumSq += (y * y).toDouble()
        if (a > peakAbs) peakAbs = a
        blockN++
        // ~10 ms at 48 kHz → refresh KPI
        if (blockN >= (sampleRate / 100).coerceAtLeast(64)) {
            lastRms = sqrt((sumSq / blockN).toFloat())
            lastPeak = peakAbs
            sumSq = 0.0
            peakAbs = 0f
            blockN = 0
        }
    }

    /** Prefer RMS for log (stable); falls back to |last|. */
    @Keep
    fun kpiLevel(): Float {
        val r = lastRms
        val p = lastPeak
        val l = abs(lastOutput)
        return when {
            r > 1e-6f -> r
            p > 1e-6f -> p * 0.5f
            else -> l
        }
    }

    @Keep
    fun reset() {
        ring.fill(0f)
        write = 0
        count = 0
        lp1 = 0f
        lp2 = 0f
        lastOutput = 0f
        lastGain = 0f
        lastRms = 0f
        lastPeak = 0f
        lastDelayUsed = 0
        sumSq = 0.0
        peakAbs = 0f
        blockN = 0
    }
}
