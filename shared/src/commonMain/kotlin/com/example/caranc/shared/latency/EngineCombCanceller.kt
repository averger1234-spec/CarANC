package com.example.caranc.shared.latency

import androidx.annotation.Keep
import kotlin.math.PI
import kotlin.math.sin

/**
 * RPM 諧波 comb feedforward：直接合成反噪，降低對低延遲 feedback 的依賴。
 */
@Keep
class EngineCombCanceller(
    private val sampleRate: Int,
    private val harmonicCount: Int = 6
) {
    private val twoPi = 2.0 * PI
    private val phases = DoubleArray(harmonicCount) { 0.0 }
    private val amplitudes = FloatArray(harmonicCount) { 1f }
    private val amplitudeEma = FloatArray(harmonicCount) { 0f }

    var rpm = 0f
        private set
    var valid = false
        private set
    var fundamentalHz = 0f
        private set
    var lastOutput = 0f
        private set

    @Keep
    fun setRpm(rpm: Float, valid: Boolean) {
        this.rpm = rpm.coerceAtLeast(0f)
        this.valid = valid
        fundamentalHz = when {
            !valid -> 0f
            rpm > 400f -> (rpm / 60f) * 2f
            rpm > 0f -> 25f
            else -> 0f
        }
    }

    @Keep
    fun feedforwardSample(micLowBand: Float): Float {
        if (!valid || fundamentalHz <= 0f) {
            lastOutput = 0f
            return 0f
        }

        val envelope = kotlin.math.abs(micLowBand)
        var output = 0f
        for (h in 0 until harmonicCount) {
            val harmonic = fundamentalHz * (h + 1)
            if (harmonic >= sampleRate / 2f) break
            val inc = twoPi * harmonic / sampleRate
            phases[h] += inc
            if (phases[h] > twoPi) phases[h] -= twoPi

            amplitudeEma[h] = 0.995f * amplitudeEma[h] + 0.005f * envelope
            val weight = when (h) {
                0 -> 0.50f
                1 -> 0.25f
                2 -> 0.12f
                else -> 0.08f / (h - 1)
            }
            amplitudes[h] = (weight * (0.4f + amplitudeEma[h] * 8f)).coerceIn(0f, 0.6f)
            output += amplitudes[h] * sin(phases[h]).toFloat()
        }

        lastOutput = (-output).coerceIn(-0.8f, 0.8f)
        return lastOutput
    }

    @Keep
    fun blendGain(idleMode: Boolean): Float = when {
        !valid || fundamentalHz <= 0f -> 0f
        idleMode && rpm in 600f..1200f -> 0.55f
        idleMode -> 0.35f
        else -> 0.12f
    }
}