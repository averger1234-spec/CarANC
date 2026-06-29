package com.example.caranc.shared.signal

import androidx.annotation.Keep

/**
 * 以播放參考估計麥克風中的音樂成分並扣除，留下環境殘差供 ANC 處理。
 */
@Keep
class MediaReferenceSubtractor(
    private val filterLength: Int = 128  // 強化：從 64 增加到 128，提供更強的音樂估計能力（尤其 AA 音樂路徑較長）
) {
    private val maxLength = 256
    private val bufferMask = 511
    private val w = FloatArray(maxLength) { 0f }
    private val refBuf = FloatArray(512) { 0f }
    private var bufferIndex = 0
    private val baseMu = 0.08f
    private val leakage = 0.9996f

    var lastSubtracted = 0f
        private set
    var lastCorrelation = 0f
        private set
    var lastActiveFilterLength = 0
        private set
    var lastMuStep = 0f
        private set
    var adaptationActive = false
        private set

    @Keep
    fun processSample(micSample: Float, playbackSample: Float, musicActive: Boolean): Float {
        refBuf[bufferIndex] = playbackSample

        var estimate = 0f
        // 更好的 musicActive 行為：
        // - musicActive 時用完整長 filter（更強扣除）
        // - !musicActive 時用極短（幾乎不扣，保留環境聲給 ANC）
        // - 同時考慮 refEnergy，低能量時保守不更新（避免在音樂很小或無效 ref 時亂調）
        val activeLength = when {
            musicActive -> filterLength.coerceAtMost(maxLength)
            else -> (filterLength / 8).coerceAtLeast(4)  // 幾乎關閉扣除，但保留少量穩定性
        }
        for (j in 0 until activeLength) {
            val idx = (bufferIndex - j) and bufferMask
            estimate += w[j] * refBuf[idx]
        }
        lastSubtracted = estimate
        lastActiveFilterLength = activeLength

        val residual = micSample - estimate
        val refEnergy = playbackSample * playbackSample + 1e-5f
        lastCorrelation = (estimate * micSample) / (refEnergy + kotlin.math.abs(micSample) + 1e-5f)

        // 強化 musicActive 行為：只有在音樂活躍 + 有足夠 ref 能量 + 相關性合理時才強力更新
        val shouldAdapt = musicActive && refEnergy > 1e-4f && kotlin.math.abs(lastCorrelation) < 2.0f
        adaptationActive = shouldAdapt
        if (shouldAdapt) {
            val adaptiveMu = baseMu * (1.0f + (kotlin.math.abs(lastCorrelation) * 0.5f)).coerceIn(0.8f, 1.5f)  // 依相關性微調步長
            val step = adaptiveMu / refEnergy
            lastMuStep = step
            for (j in 0 until activeLength) {
                val idx = (bufferIndex - j) and bufferMask
                w[j] = (w[j] * leakage + step * residual * refBuf[idx]).coerceIn(-1.5f, 1.5f)
            }
        } else {
            lastMuStep = 0f
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
        lastActiveFilterLength = 0
        lastMuStep = 0f
        adaptationActive = false
    }
}