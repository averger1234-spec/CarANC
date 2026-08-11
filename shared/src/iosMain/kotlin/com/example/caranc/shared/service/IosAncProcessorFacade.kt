package com.example.caranc.shared.service

import com.example.caranc.shared.*
import com.example.caranc.shared.latency.LatencyBandLimits
import com.example.caranc.shared.model.CabinTransferModel
import com.example.caranc.shared.model.NoiseBandClassification

/**
 * iOS facade：直接包一層 commonMain 的 [MultiBandANCProcessor]。
 *
 * Swift 端 AVAudioEngine 也可獨立實作 DSP（見 iosApp/）；
 * 此 facade 供 KMP framework 連結時使用同一套演算法核心。
 */
class IosAncProcessorFacade(
    sampleRate: Int,
    bufferSize: Int,
    initialTier: UserTier = UserTier.STANDARD,
    sessionContext: AncSessionContext = GlobalAncSessionContext
) : AncProcessorFacade by MultiBandANCProcessor(
    sampleRate = sampleRate,
    bufferSize = bufferSize,
    initialTier = initialTier,
    sessionContext = sessionContext
)
