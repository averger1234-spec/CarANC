package com.example.caranc.shared.signal

import androidx.annotation.Keep

/**
 * 以播放參考估計麥克風中的音樂成分並扣除，留下環境殘差供 ANC 處理。
 */
@Keep
class MediaReferenceSubtractor(
    private val filterLength: Int = 64
) {
    private val maxLength = 128
    private val bufferMask = 255
    private val w = FloatArray(maxLength) { 0f }
    private val refBuf = FloatArray(256) { 0f }
    private var bufferIndex = 0
    private val mu = 0.08f
    private val leakage = 0.9996f

    var lastSubtracted = 0f
        private set
    var lastCorrelation = 0f
        private set

    @Keep
    fun processSample(micSample: Float, playbackSample: Float, musicActive: Boolean): Float {
        refBuf[bufferIndex] = playbackSample

        var estimate = 0f
        val activeLength = if (musicActive) filterLength else (filterLength / 4)
        for (j in 0 until activeLength.coerceAtMost(maxLength)) {
            val idx = (bufferIndex - j) and bufferMask
            estimate += w[j] * refBuf[idx]
        }
        lastSubtracted = estimate

        val residual = micSample - estimate
        val refEnergy = playbackSample * playbackSample + 1e-5f
        lastCorrelation = (estimate * micSample) / (refEnergy + kotlin.math.abs(micSample) + 1e-5f)

        if (musicActive && refEnergy > 1e-4f) {
            val step = mu / refEnergy
            for (j in 0 until activeLength.coerceAtMost(maxLength)) {
                val idx = (bufferIndex - j) and bufferMask
                w[j] = (w[j] * leakage + step * residual * refBuf[idx]).coerceIn(-1.2f, 1.2f)
            }
        }

        bufferIndex = (bufferIndex + 1) and bufferMask
        return residual
    }

    @Keep
    fun reset() {
        w.fill(0f)
        refBuf.fill(0f)
        bufferIndex = 0
        lastSubtracted = 0f
        lastCorrelation = 0f
    }
}