package com.example.caranc.shared.signal

import androidx.annotation.Keep
import kotlin.math.abs

/**
 * 偵測系統通知鈴聲 / sonification 類短暫事件（USAGE_NOTIFICATION, USAGE_ASSISTANCE_SONIFICATION 等）。
 * 目標：在高延遲 AA 環境下，當 notification 出現時，快速觸發 ANC 輸出大幅 duck + LMS freeze，
 * 避免：
 *   - notification 被 ANC 當 noise 處理產生 echo（延遲 anti-noise 疊加）
 *   - ANC 干擾 AA 主音訊 routing 造成 choppy / buffer underrun
 *   - LMS 對短暫尖峰做不必要適應產生 telegraph / artifact
 *
 * 與 SirenDetector 互補：
 *   - Siren：窄頻掃頻長事件（救護車），長 hold。
 *   - Sonification：短促蜂鳴/提示音（<1s），短 hold + 極低 gain。
 *
 * 觸發後會透過 setSonificationOverride 影響：
 *   - 各 band muScale * eventScale（降低學習）
 *   - 最終 anti-noise 輸出 * eventScale（保護重要聲音不被「取消」）
 *   - freeze = ... || sonificationOverride（保護適應）
 */
@Keep
class SonificationDetector(sampleRate: Int) {
    // 短事件特性：快速能量上升 + 快速衰減。使用雙 EMA（快攻擊 / 慢釋放）偵測 burst。
    private val fastCoeff = (2.0 * Math.PI * 20.0 / sampleRate).toFloat().coerceIn(0.05f, 0.3f)  // ~20Hz envelope
    private val slowCoeff = (2.0 * Math.PI * 5.0 / sampleRate).toFloat().coerceIn(0.01f, 0.1f)

    private var envFast = 0f
    private var envSlow = 0f
    private var burstHold = 0
    private var activeHold = 0

    private var lastBurstRms = 0f

    var isSonificationActive = false
        private set
    var sonificationConfidence = 0f
        private set
    var burstRatio = 0f   // fast vs slow envelope ratio (high = sudden transient)

    @Keep
    fun processSample(sample: Float): Boolean {
        val absSample = abs(sample)

        envFast += fastCoeff * (absSample - envFast)
        envSlow += slowCoeff * (absSample - envSlow)

        val ratio = if (envSlow > 1e-6f) envFast / envSlow else 0f
        burstRatio = 0.9f * burstRatio + 0.1f * ratio.coerceIn(0f, 8f)

        // 短促高能量 burst：ratio 高 + 絕對能量足夠（notification 通常比環境 noise 突出）
        val isBurst = burstRatio > 2.2f && envFast > 0.008f

        if (isBurst) {
            burstHold = 25          // ~50ms @48k/64samp blocks 左右窗口
            lastBurstRms = envFast
        } else {
            burstHold = (burstHold - 1).coerceAtLeast(0)
        }

        // active hold 比 burst 長一些，讓整個 notification 聲音（含尾音）都被保護
        if (burstHold > 8) {
            activeHold = 55   // 約 110-120ms 保護窗口，足夠大多數提示音；可依實測調
        } else {
            activeHold = (activeHold - 1).coerceAtLeast(0)
        }

        isSonificationActive = activeHold > 12
        sonificationConfidence = when {
            activeHold > 40 -> 0.85f
            activeHold > 20 -> 0.6f
            burstHold > 5 -> 0.4f
            else -> 0f
        }
        return isSonificationActive
    }

    @Keep
    fun ancGainScale(): Float = when {
        !isSonificationActive -> 1f
        sonificationConfidence > 0.7f -> 0.06f   // 極低，幾乎 bypass ANC 輸出，避免 echo
        else -> 0.18f
    }

    @Keep
    fun reset() {
        envFast = 0f
        envSlow = 0f
        burstHold = 0
        activeHold = 0
        isSonificationActive = false
        sonificationConfidence = 0f
        burstRatio = 0f
        lastBurstRms = 0f
    }
}
