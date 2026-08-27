package com.example.caranc.shared.latency

import com.example.caranc.shared.Keep
import kotlin.math.PI
import kotlin.math.min

/**
 * Peak-aligned feedforward for AA (~100–140 ms plant).
 *
 * LMS on delayed error cannot lock 150–400 / 500+ Hz when D is tens of cycles.
 * This path band-passes the cabin mic at the **live F0**, reads x(n−D) with the
 * chased plant delay, and emits −delayed. Chase D so 2D ≈ k/F0; if residual
 * says adding, the caller must mute (錯相就闭嘴).
 */
@Keep
class PeakAlignedDelayCancel(
    private val sampleRate: Int,
    capacity: Int = 16384
) {
    private val ring = FloatArray(capacity.coerceAtLeast(2048))
    private var write = 0
    private var count = 0
    private var f0Hz = 0f
    private var lpLo = 0f
    private var lpHi = 0f

    var lastOutput = 0f
        private set
    var lastF0Hz = 0f
        private set

    @Keep
    fun setF0(hz: Float) {
        f0Hz = if (hz in 30f..4000f) hz else 0f
        lastF0Hz = f0Hz
        if (f0Hz < 30f) {
            lpLo = 0f
            lpHi = 0f
        }
    }

    @Keep
    fun push(x: Float) {
        val sample = x.coerceIn(-1.5f, 1.5f)
        val bp = if (f0Hz < 30f) {
            0f
        } else {
            val lo = (2.0 * PI * (f0Hz / 1.45f) / sampleRate).toFloat().coerceIn(0.002f, 0.65f)
            val hi = (2.0 * PI * (f0Hz * 1.45f) / sampleRate).toFloat().coerceIn(0.003f, 0.85f)
            lpHi += hi * (sample - lpHi)
            lpLo += lo * (sample - lpLo)
            (lpHi - lpLo).coerceIn(-1.2f, 1.2f)
        }
        ring[write % ring.size] = bp
        write++
        if (count < ring.size) count++
    }

    @Keep
    fun process(
        plantDelaySamples: Int,
        gain: Float,
        polarity: Float,
        enable: Boolean
    ): Float {
        if (!enable || f0Hz < 30f || count < 16) {
            lastOutput *= 0.90f
            return lastOutput
        }
        val d = plantDelaySamples.coerceIn(0, min(count - 1, ring.size - 1))
        val delayed = ring[(write - 1 - d).mod(ring.size)]
        val pol = if (polarity >= 0f) 1f else -1f
        lastOutput = (-delayed * gain * pol).coerceIn(-0.85f, 0.85f)
        return lastOutput
    }

    @Keep
    fun reset() {
        ring.fill(0f)
        write = 0
        count = 0
        f0Hz = 0f
        lpLo = 0f
        lpHi = 0f
        lastOutput = 0f
        lastF0Hz = 0f
    }
}
