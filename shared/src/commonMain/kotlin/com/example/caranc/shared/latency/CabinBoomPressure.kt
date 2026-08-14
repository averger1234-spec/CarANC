package com.example.caranc.shared.latency

import com.example.caranc.shared.Keep
import kotlin.math.abs
import kotlin.math.min

/**
 * **Cabin boom pressure path** — put real low-frequency anti energy on the speakers for 悶.
 *
 * Product feedback: user hears electronic noise with **zero** muffle-cancel feel; asking to
 * "play less" was wrong. This path deliberately produces **band-limited low-frequency
 * sound pressure** (≈30–100 Hz) correlated with cabin low-band mic (plant-delayed invert),
 * not broadband sand and not free-running multi-tone synth.
 *
 * Mechanism:
 * - Ring-buffer the low-band mic sample
 * - Read x(n − D) where D ≈ AA plant electrical delay
 * - Output anti = −gain · lowpass(x(n−D))  (speaker polarity)
 * - Gain scales with speed + |low| energy so cruise boom gets pressure
 */
@Keep
class CabinBoomPressure(
    private val sampleRate: Int,
    capacity: Int = 16384
) {
    private val ring = FloatArray(capacity.coerceAtLeast(2048))
    private var write = 0
    private var count = 0

    // Very-low LPF states (~70 Hz) — keep only boom, kill electronic mid/high
    private var lp1 = 0f
    private var lp2 = 0f
    private val lpCoeff: Float =
        (2.0 * kotlin.math.PI * 70.0 / sampleRate).toFloat().coerceIn(0.004f, 0.08f)

    var lastOutput = 0f
        private set
    var lastGain = 0f
        private set

    @Keep
    fun push(lowBandSample: Float) {
        ring[write % ring.size] = lowBandSample.coerceIn(-1.5f, 1.5f)
        write++
        if (count < ring.size) count++
    }

    /**
     * @param plantDelaySamples electrical delay (track+framework) at process sample rate
     * @param speedKmh vehicle speed
     * @param boomPriority ROAD / rumble focus
     * @return speaker anti sample (already − polarity, lowpassed)
     */
    @Keep
    fun process(
        plantDelaySamples: Int,
        speedKmh: Float,
        boomPriority: Boolean,
        freeze: Boolean
    ): Float {
        if (!boomPriority || speedKmh < 18f || count < 8) {
            lastOutput *= 0.95f
            lastGain = 0f
            return lastOutput
        }
        val d = plantDelaySamples.coerceIn(0, min(count - 1, ring.size - 1))
        // Prefer a usable delay floor: if measured plant is tiny, still use ~20ms for acoustic
        val dUse = if (d < sampleRate / 80) (sampleRate / 50).coerceAtMost(count - 1) else d
        val idx = (write - 1 - dUse).mod(ring.size)
        val delayed = ring[idx]

        // Speed + energy drive how hard we push LF pressure (not a free oscillator)
        val speedNorm = ((speedKmh - 15f) / 70f).coerceIn(0.15f, 1.25f)
        val energy = abs(delayed).coerceIn(0f, 0.8f)
        // Strong enough to move cabin SPL; not full-scale clip
        val gain = (0.42f + 0.55f * speedNorm) * (0.35f + 1.4f * energy).coerceIn(0.25f, 1.35f)
        lastGain = if (freeze) lastGain * 0.99f else gain

        var y = -delayed * lastGain
        // Dual 1-pole ~70 Hz: boom pressure only
        lp1 += lpCoeff * (y - lp1)
        lp2 += lpCoeff * (lp1 - lp2)
        y = lp2.coerceIn(-0.85f, 0.85f)
        lastOutput = y
        return y
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
    }
}
