package com.example.caranc.shared.latency

import com.example.caranc.shared.Keep
import com.example.caranc.shared.model.NvhFocusClass
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * **Two-weight (sin/cos) adaptive narrowband cancellers** for product NVH that
 * broadband mid/high LMS cannot phase-lock under AA delay:
 *
 * - **Tire**: 3 speed-scheduled peaks (~180–360 Hz class)
 * - **Wind**: 6 fixed HF notches (~550–2400 Hz) for active aero suppress
 *
 * Each channel: y = w1·cos(ωn) + w2·sin(ωn); anti contribution = −y;
 * w ← αw + μ·e·ref (leaky LMS). Not a UI placebo — real anti samples mixed into the path.
 */
@Keep
class AdaptiveNarrowbandBank(
    private val sampleRate: Int
) {
    private data class Channel(
        var w1: Float = 0f,
        var w2: Float = 0f,
        var phase: Float = 0f,
        var freqHz: Float = 0f,
        var lastY: Float = 0f
    )

    private val tire = Array(3) { Channel() }
    private val wind = Array(6) { Channel() }

    /** Sum |y| EMA for log. */
    var lastTireNotchEnergy: Float = 0f
        private set
    var lastWindNotchEnergy: Float = 0f
        private set
    var lastTireF0Hz: Float = 0f
        private set
    var lastWindActiveCount: Int = 0
        private set
    var lastMixAnti: Float = 0f
        private set

    private var tireEnergyEma = 0f
    private var windEnergyEma = 0f

    /**
     * @param errorSample mic / virtual residual (drive adaptation)
     * @param focus current NVH class
     * @param speedKmh vehicle speed
     * @param speedValid GPS/fusion valid
     * @return anti sample to **add** to combined speaker anti (already −y polarity)
     */
    fun process(
        errorSample: Float,
        focus: NvhFocusClass,
        speedKmh: Float,
        speedValid: Boolean,
        freeze: Boolean
    ): Float {
        val tireOn = focus == NvhFocusClass.TIRE_NOISE ||
            (focus == NvhFocusClass.ROAD_RUMBLE && speedValid && speedKmh >= 40f)
        val windOn = focus == NvhFocusClass.WIND_SHEAR

        var anti = 0f
        var tireE = 0f
        var windE = 0f
        var windN = 0

        if (tireOn && speedValid && speedKmh >= 20f) {
            val freqs = tireFrequenciesHz(speedKmh)
            lastTireF0Hz = freqs[0]
            val mu = 0.0045f
            val leak = 0.9992f
            for (i in tire.indices) {
                val y = stepChannel(
                    ch = tire[i],
                    freqHz = freqs[i],
                    error = errorSample,
                    mu = mu,
                    leak = leak,
                    freeze = freeze,
                    active = true
                )
                anti += -y * tireGain(i)
                tireE += if (y >= 0f) y else -y
            }
        } else {
            // Slow decay weights when off
            for (ch in tire) {
                ch.w1 *= 0.995f
                ch.w2 *= 0.995f
            }
        }

        if (windOn) {
            val freqs = WIND_HZ
            val mu = 0.0035f
            val leak = 0.9988f
            for (i in wind.indices) {
                val y = stepChannel(
                    ch = wind[i],
                    freqHz = freqs[i],
                    error = errorSample,
                    mu = mu,
                    leak = leak,
                    freeze = freeze,
                    active = true
                )
                anti += -y * windGain(i)
                val ay = if (y >= 0f) y else -y
                windE += ay
                if (ay > 1e-5f) windN++
            }
        } else {
            for (ch in wind) {
                ch.w1 *= 0.995f
                ch.w2 *= 0.995f
            }
        }

        // Soft clip notch mix so it cannot dominate speaker
        anti = anti.coerceIn(-0.55f, 0.55f)
        lastMixAnti = anti
        tireEnergyEma = 0.9f * tireEnergyEma + 0.1f * tireE
        windEnergyEma = 0.9f * windEnergyEma + 0.1f * windE
        lastTireNotchEnergy = tireEnergyEma
        lastWindNotchEnergy = windEnergyEma
        lastWindActiveCount = windN
        return anti
    }

    private fun stepChannel(
        ch: Channel,
        freqHz: Float,
        error: Float,
        mu: Float,
        leak: Float,
        freeze: Boolean,
        active: Boolean
    ): Float {
        if (!active || freqHz < 30f || freqHz > sampleRate * 0.45f) {
            ch.lastY = 0f
            return 0f
        }
        ch.freqHz = freqHz
        val w = (2.0 * PI * freqHz / sampleRate).toFloat()
        ch.phase += w
        // wrap ~2π
        val twoPi = (2.0 * PI).toFloat()
        if (ch.phase > twoPi) ch.phase -= twoPi
        if (ch.phase < 0f) ch.phase += twoPi
        val c = cos(ch.phase)
        val s = sin(ch.phase)
        val y = ch.w1 * c + ch.w2 * s
        if (!freeze) {
            // Adapt to drive residual error toward 0 at this frequency
            ch.w1 = leak * ch.w1 + mu * error * c
            ch.w2 = leak * ch.w2 + mu * error * s
            // Bound weights
            ch.w1 = ch.w1.coerceIn(-1.5f, 1.5f)
            ch.w2 = ch.w2.coerceIn(-1.5f, 1.5f)
        }
        ch.lastY = y
        return y
    }

    /** Speed-scheduled tire cavity / tread peak estimates (Hz). */
    fun tireFrequenciesHz(speedKmh: Float): FloatArray {
        val v = speedKmh.coerceIn(20f, 130f)
        // Rough physical prior: tonal components rise with speed; clamp to tire band.
        val f0 = (95f + v * 1.35f).coerceIn(160f, 280f)
        val f1 = (130f + v * 1.85f).coerceIn(200f, 340f)
        val f2 = (170f + v * 2.15f).coerceIn(240f, 400f)
        return floatArrayOf(f0, f1, f2)
    }

    companion object {
        val WIND_HZ = floatArrayOf(550f, 750f, 1000f, 1400f, 1800f, 2400f)

        private fun tireGain(i: Int): Float = when (i) {
            0 -> 0.55f
            1 -> 0.40f
            else -> 0.28f
        }

        private fun windGain(i: Int): Float = when {
            i <= 1 -> 0.35f
            i <= 3 -> 0.28f
            else -> 0.20f
        }
    }
}
