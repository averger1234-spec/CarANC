package com.example.caranc.shared.latency

import com.example.caranc.shared.Keep
import com.example.caranc.shared.model.NvhFocusClass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Narrowband anti for cabin NVH.
 *
 * **Boom cancel under AA (1.2.5)**: free-running open-loop sin = hiss. Real path is
 * **complex LMS** (w1·cos + w2·sin): oscillator is an internal reference; weights learn
 * **phase + amplitude** of the residual boom (~50–110 Hz). That is classic narrowband ANC
 * and works under long delay for quasi-stationary cabin boom (field rec peak ~65 Hz).
 *
 * Output is **weight-gated**: silent until |w| grows → no sand at cold start.
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

    /** 3 boom lines (recording: energy ~95% <150Hz, peak ~65Hz). */
    private val road = Array(3) { Channel() }
    private val tire = Array(3) { Channel() }
    private val wind = Array(6) { Channel() }

    var lastTireNotchEnergy: Float = 0f
        private set
    var lastWindNotchEnergy: Float = 0f
        private set
    var lastRoadNotchEnergy: Float = 0f
        private set
    var lastTireF0Hz: Float = 0f
        private set
    var lastWindActiveCount: Int = 0
        private set
    var lastMixAnti: Float = 0f
        private set
    var lastMixScale: Float = 1f
        private set
    var lastOpenLoopUsed: Float = 0f
        private set
    /** Sum |w| on road channels — log: >0 means boom phase is locking. */
    var lastRoadWeightEnergy: Float = 0f
        private set

    private var tireEnergyEma = 0f
    private var windEnergyEma = 0f
    private var roadEnergyEma = 0f

    /**
     * @param allowOpenLoop always false on AA product path
     * @param highLatency AA path: no HF wind; **stronger** adaptive boom (not weaker)
     * @param boomPriority ROAD_RUMBLE / drive low-lock — raise mu + mix
     */
    fun process(
        errorSample: Float,
        focus: NvhFocusClass,
        speedKmh: Float,
        speedValid: Boolean,
        freeze: Boolean,
        allowOpenLoop: Boolean = false,
        highLatency: Boolean = true,
        boomPriority: Boolean = false
    ): Float {
        val spdOk = speedValid && speedKmh >= 15f
        val roadOn = spdOk && (
            focus == NvhFocusClass.ROAD_RUMBLE ||
                focus == NvhFocusClass.TIRE_NOISE ||
                focus == NvhFocusClass.MIXED_CABIN ||
                (boomPriority && focus != NvhFocusClass.IDLE)
            )
        val tireOn = spdOk && speedKmh >= 32f && (
            focus == NvhFocusClass.TIRE_NOISE ||
                (!highLatency && focus == NvhFocusClass.MIXED_CABIN)
            )
        // HF under AA delay → sand; only low-lat wind
        val windOn = spdOk && focus == NvhFocusClass.WIND_SHEAR && !highLatency

        var anti = 0f
        var tireE = 0f
        var windE = 0f
        var roadE = 0f
        var windN = 0
        var roadW = 0f
        val useOl = allowOpenLoop && !highLatency
        lastOpenLoopUsed = if (useOl) 1f else 0f

        // --- Road boom: primary 悶 cancel weapon under AA ---
        if (roadOn) {
            val freqs = roadBoomHz(speedKmh)
            // Faster phase lock on boom; high-lat needs MORE mu on narrowband, not less
            val mu = when {
                boomPriority && highLatency -> 0.014f
                boomPriority -> 0.012f
                highLatency -> 0.010f
                else -> 0.0075f
            }
            val leak = 0.9992f
            val olBase = if (useOl) 0.04f else 0f
            val wClip = if (boomPriority) 2.0f else 1.4f
            for (i in road.indices) {
                val yAdapt = stepChannel(road[i], freqs[i], errorSample, mu, leak, freeze, true, wClip)
                val we = abs(road[i].w1) + abs(road[i].w2)
                roadW += we
                // Gate: only inject after weights leave zero (phase acquisition)
                val gate = (we / 0.08f).coerceIn(0f, 1f)
                val ol = olBase * if (i == 0) 1f else 0.7f
                val y = (yAdapt + ol * sin(road[i].phase)) * gate
                anti += -y * roadGain(i, boomPriority)
                roadE += abs(y)
            }
        } else {
            for (ch in road) {
                ch.w1 *= 0.992f
                ch.w2 *= 0.992f
            }
        }
        lastRoadWeightEnergy = roadW

        if (tireOn) {
            val freqs = tireFrequenciesHz(speedKmh)
            lastTireF0Hz = freqs[0]
            val mu = if (highLatency) 0.006f else 0.008f
            val leak = 0.9988f
            for (i in tire.indices) {
                val yAdapt = stepChannel(tire[i], freqs[i], errorSample, mu, leak, freeze, true, 1.2f)
                val we = abs(tire[i].w1) + abs(tire[i].w2)
                val gate = (we / 0.10f).coerceIn(0f, 1f)
                val y = yAdapt * gate
                anti += -y * tireGain(i)
                tireE += abs(y)
            }
        } else {
            lastTireF0Hz = 0f
            for (ch in tire) {
                ch.w1 *= 0.994f
                ch.w2 *= 0.994f
            }
        }

        if (windOn) {
            val mu = 0.006f
            val leak = 0.9985f
            for (i in wind.indices) {
                val yAdapt = stepChannel(wind[i], WIND_HZ[i], errorSample, mu, leak, freeze, true, 1.0f)
                val we = abs(wind[i].w1) + abs(wind[i].w2)
                val gate = (we / 0.12f).coerceIn(0f, 1f)
                val y = yAdapt * gate
                anti += -y * windGain(i)
                val ay = abs(y)
                windE += ay
                if (ay > 1e-4f) windN++
            }
        } else {
            for (ch in wind) {
                ch.w1 *= 0.994f
                ch.w2 *= 0.994f
            }
        }

        // Boom under high-lat: **raise** mix (weights gated → no cold sand)
        val mixScale = when {
            boomPriority && highLatency -> 1.85f
            boomPriority -> 1.55f
            highLatency && focus == NvhFocusClass.ROAD_RUMBLE -> 1.45f
            highLatency && focus == NvhFocusClass.TIRE_NOISE -> 1.15f
            highLatency -> 0.90f
            focus == NvhFocusClass.ROAD_RUMBLE -> 1.40f
            focus == NvhFocusClass.TIRE_NOISE -> 1.35f
            focus == NvhFocusClass.WIND_SHEAR -> 1.20f
            focus == NvhFocusClass.MIXED_CABIN -> 1.15f
            else -> 0.35f
        }
        lastMixScale = mixScale
        anti = (anti * mixScale).coerceIn(-0.85f, 0.85f)
        lastMixAnti = anti

        roadEnergyEma = 0.88f * roadEnergyEma + 0.12f * roadE
        tireEnergyEma = 0.88f * tireEnergyEma + 0.12f * tireE
        windEnergyEma = 0.88f * windEnergyEma + 0.12f * windE
        lastRoadNotchEnergy = roadEnergyEma
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
        active: Boolean,
        wClip: Float
    ): Float {
        if (!active || freqHz < 30f || freqHz > sampleRate * 0.45f) {
            ch.lastY = 0f
            return 0f
        }
        ch.freqHz = freqHz
        val w = (2.0 * PI * freqHz / sampleRate).toFloat()
        ch.phase += w
        val twoPi = (2.0 * PI).toFloat()
        if (ch.phase > twoPi) ch.phase -= twoPi
        if (ch.phase < 0f) ch.phase += twoPi
        val c = cos(ch.phase)
        val s = sin(ch.phase)
        val y = ch.w1 * c + ch.w2 * s
        if (!freeze) {
            // Complex LMS: minimize residual → learn anti phase through plant
            ch.w1 = leak * ch.w1 + mu * error * c
            ch.w2 = leak * ch.w2 + mu * error * s
            ch.w1 = ch.w1.coerceIn(-wClip, wClip)
            ch.w2 = ch.w2.coerceIn(-wClip, wClip)
        }
        ch.lastY = y
        return y
    }

    /** Boom lines from cabin rec (~65 Hz peak) + speed drift. */
    fun roadBoomHz(speedKmh: Float): FloatArray {
        val v = speedKmh.coerceIn(15f, 130f)
        val f0 = (48f + v * 0.18f).coerceIn(45f, 72f)   // lower boom
        val f1 = (58f + v * 0.22f).coerceIn(55f, 85f)   // peak ~65 at mid speed
        val f2 = (78f + v * 0.30f).coerceIn(72f, 115f)  // upper cabin mode
        return floatArrayOf(f0, f1, f2)
    }

    fun tireFrequenciesHz(speedKmh: Float): FloatArray {
        val v = speedKmh.coerceIn(20f, 130f)
        val f0 = (100f + v * 1.25f).coerceIn(150f, 260f)
        val f1 = (145f + v * 1.75f).coerceIn(190f, 330f)
        val f2 = (185f + v * 2.05f).coerceIn(230f, 400f)
        return floatArrayOf(f0, f1, f2)
    }

    companion object {
        val WIND_HZ = floatArrayOf(550f, 750f, 1000f, 1400f, 1800f, 2400f)

        private fun roadGain(i: Int, boomPriority: Boolean): Float {
            val b = if (boomPriority) 1.15f else 1f
            return b * when (i) {
                0 -> 1.0f
                1 -> 1.10f // peak line slightly hotter
                else -> 0.80f
            }
        }

        private fun tireGain(i: Int): Float = when (i) {
            0 -> 0.75f
            1 -> 0.60f
            else -> 0.48f
        }

        private fun windGain(i: Int): Float = when {
            i <= 1 -> 0.50f
            i <= 3 -> 0.38f
            else -> 0.28f
        }
    }
}
