package com.example.caranc.shared.signal

import androidx.annotation.Keep
import kotlin.math.PI
import kotlin.math.sin

/**
 * 依引擎 RPM 產生諧波參考（怠速基頻 + 2/3 次諧波），供低頻 FXLMS 參考混合。
 */
@Keep
class EngineHarmonicGenerator(private val sampleRate: Int) {
    private val twoPi = 2.0 * PI
    private var phase1 = 0.0
    private var phase2 = 0.0
    private var phase3 = 0.0

    var rpm = 0f
        private set
    var valid = false
        private set
    var fundamentalHz = 0f
        private set
    var lastReference = 0f
        private set

    @Keep
    fun setRpm(rpm: Float, valid: Boolean) {
        this.rpm = rpm.coerceAtLeast(0f)
        this.valid = valid
        fundamentalHz = if (valid && rpm > 400f) {
            (rpm / 60f) * 2f
        } else if (valid && rpm > 0f) {
            25f
        } else {
            0f
        }
    }

    @Keep
    fun nextSample(): Float {
        if (!valid || fundamentalHz <= 0f) {
            lastReference = 0f
            return 0f
        }

        val inc1 = twoPi * fundamentalHz / sampleRate
        val inc2 = inc1 * 2.0
        val inc3 = inc1 * 3.0

        phase1 += inc1
        phase2 += inc2
        phase3 += inc3
        if (phase1 > twoPi) phase1 -= twoPi
        if (phase2 > twoPi) phase2 -= twoPi
        if (phase3 > twoPi) phase3 -= twoPi

        val ref = (
            0.55f * sin(phase1).toFloat() +
                0.30f * sin(phase2).toFloat() +
                0.15f * sin(phase3).toFloat()
            ).coerceIn(-1f, 1f)
        lastReference = ref
        return ref
    }

    @Keep
    fun blendWeight(idleMode: Boolean): Float = when {
        !valid || fundamentalHz <= 0f -> 0f
        idleMode && rpm in 600f..1200f -> 0.45f
        idleMode -> 0.25f
        else -> 0.1f
    }
}