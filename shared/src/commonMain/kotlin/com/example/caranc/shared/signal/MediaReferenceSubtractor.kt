package com.example.caranc.shared.signal

import androidx.annotation.Keep

/**
 * 以播放參考估計麥克風中的音樂成分並扣除，留下環境殘差供 ANC 處理。
 */
@Keep
class MediaReferenceSubtractor(
    private val filterLength: Int = 256  // P1: default 256 (support up to 512) for stronger music path estimation in AA/remote long-delay scenarios. Analysis of 20260630 log showed mediaSubtracted always ~0 with prior 128.
) {
    private val maxLength = 512  // raised to allow longer adaptive filters when musicActive
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
        // 強化 musicActive 行為：
        // - musicActive 時用完整長 filter（更強扣除 AA 長路徑音樂）
        // - !musicActive 時用極短（保留環境 rumble 給 ANC）
        val activeLength = when {
            musicActive -> filterLength.coerceAtMost(maxLength)
            else -> (filterLength / 8).coerceAtLeast(4)
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

        // Correlation-driven 動態 mu (按 user 分析建議強化)
        // 音樂 + 高相關 → 大幅加快學習扣除；音樂但低相關 → 保守或保護 rumble；無音樂 → 大幅降低
        val absCorr = kotlin.math.abs(lastCorrelation)
        val shouldAdapt = refEnergy > 1e-4f && absCorr < 3.0f
        adaptationActive = shouldAdapt && (musicActive || absCorr > 0.15f)  // 即使無音樂，若有明顯相關也允許少量

        if (shouldAdapt) {
            val corrBoost = if (musicActive && absCorr > 0.3f) {
                1.0f + 3.0f * absCorr.coerceAtMost(0.9f)   // 高相關時強力扣除音樂
            } else if (musicActive) {
                0.6f   // 音樂存在但相關低，保守保護低頻 rumble
            } else {
                0.15f
            }
            // P1 review enhancement: music energy dominant guard (estimate high vs mic) -> further reduce to protect rumble
            val musicDominantFactor = if (musicActive && estimate > kotlin.math.abs(micSample) * 0.4f) 0.5f else 1f
            val adaptiveMu = baseMu * corrBoost.coerceIn(0.1f, 4.0f) * musicDominantFactor
            val step = adaptiveMu / refEnergy
            lastMuStep = step
            for (j in 0 until activeLength) {
                val idx = (bufferIndex - j) and bufferMask
                w[j] = (w[j] * leakage + step * residual * refBuf[idx]).coerceIn(-2.0f, 2.0f)
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