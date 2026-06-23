package com.example.caranc.shared.signal

import androidx.annotation.Keep
import kotlin.math.PI

/**
 * 通話時保護語音頻帶（約 300–3400 Hz），降低中頻 ANC 增益。
 */
@Keep
class VoiceBandProtector(sampleRate: Int) {
    private val voiceLowHz = 300f
    private val voiceHighHz = 3400f
    private val coeffLow = (2.0 * PI * voiceLowHz / sampleRate).toFloat().coerceIn(0.01f, 0.2f)
    private val coeffHigh = (2.0 * PI * voiceHighHz / sampleRate).toFloat().coerceIn(0.05f, 0.35f)

    private var lpLow = 0f
    private var lpHigh = 0f
    private var voiceEnergyEma = 0f

    var voiceBandGain = 1f
        private set
    var voiceEnergyRatio = 0f
        private set

    @Keep
    fun update(callActive: Boolean, sample: Float): Float {
        lpLow += coeffLow * (sample - lpLow)
        lpHigh += coeffHigh * (sample - lpHigh)
        val voiceBand = (lpHigh - lpLow).coerceIn(-1f, 1f)
        val voiceEnergy = voiceBand * voiceBand
        voiceEnergyEma = 0.98f * voiceEnergyEma + 0.02f * voiceEnergy
        voiceEnergyRatio = voiceEnergyEma

        voiceBandGain = when {
            !callActive -> 1f
            voiceEnergyEma > 0.002f -> 0.05f
            else -> 0.15f
        }
        return voiceBandGain
    }

    @Keep
    fun midBandMuScale(callActive: Boolean): Float =
        if (callActive) voiceBandGain else 1f

    @Keep
    fun highBandMuScale(callActive: Boolean): Float =
        if (callActive) voiceBandGain * 0.5f else 1f
}