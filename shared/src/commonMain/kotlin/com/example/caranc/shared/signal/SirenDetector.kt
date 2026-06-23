package com.example.caranc.shared.signal

import androidx.annotation.Keep
import kotlin.math.PI
import kotlin.math.abs

/**
 * 偵測救護車/警笛類窄頻掃頻音，觸發時暫停或降低 ANC。
 */
@Keep
class SirenDetector(sampleRate: Int) {
    private val bandLowHz = 450f
    private val bandHighHz = 1600f
    private val coeffLow = (2.0 * PI * bandLowHz / sampleRate).toFloat().coerceIn(0.01f, 0.2f)
    private val coeffHigh = (2.0 * PI * bandHighHz / sampleRate).toFloat().coerceIn(0.05f, 0.3f)

    private var lpLow = 0f
    private var lpHigh = 0f
    private var bandEnergyEma = 0f
    private var totalEnergyEma = 0f
    private var peakHold = 0
    private var activeHold = 0

    var isSirenActive = false
        private set
    var sirenConfidence = 0f
        private set
    var bandEnergyRatio = 0f
        private set

    @Keep
    fun processSample(sample: Float): Boolean {
        lpLow += coeffLow * (sample - lpLow)
        lpHigh += coeffHigh * (sample - lpHigh)
        val band = (lpHigh - lpLow).coerceIn(-1f, 1f)
        val bandEnergy = band * band
        val totalEnergy = sample * sample

        bandEnergyEma = 0.97f * bandEnergyEma + 0.03f * bandEnergy
        totalEnergyEma = 0.97f * totalEnergyEma + 0.03f * totalEnergy
        bandEnergyRatio = bandEnergyEma / (totalEnergyEma + 1e-6f)

        val strongNarrowband = bandEnergyEma > 0.004f && bandEnergyRatio > 0.55f
        val moderateNarrowband = bandEnergyEma > 0.002f && bandEnergyRatio > 0.42f

        if (strongNarrowband) {
            peakHold = 40
        } else if (moderateNarrowband) {
            peakHold = (peakHold + 8).coerceAtMost(40)
        } else {
            peakHold = (peakHold - 1).coerceAtLeast(0)
        }

        if (peakHold > 20) {
            activeHold = 80
        } else if (peakHold > 10) {
            activeHold = (activeHold + 4).coerceAtMost(80)
        } else {
            activeHold = (activeHold - 1).coerceAtLeast(0)
        }

        isSirenActive = activeHold > 30
        sirenConfidence = when {
            activeHold > 50 -> 0.9f
            activeHold > 30 -> 0.6f
            peakHold > 15 -> 0.35f
            else -> 0f
        }
        return isSirenActive
    }

    @Keep
    fun ancGainScale(): Float = when {
        !isSirenActive -> 1f
        sirenConfidence > 0.7f -> 0.05f
        else -> 0.25f
    }

    @Keep
    fun reset() {
        lpLow = 0f
        lpHigh = 0f
        bandEnergyEma = 0f
        totalEnergyEma = 0f
        peakHold = 0
        activeHold = 0
        isSirenActive = false
        sirenConfidence = 0f
        bandEnergyRatio = 0f
    }
}