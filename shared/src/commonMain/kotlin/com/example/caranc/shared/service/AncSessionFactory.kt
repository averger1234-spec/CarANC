package com.example.caranc.shared.service

import com.example.caranc.shared.*
import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext

/**
 * Light DI / factory for ANC session components (Cycle3 P2).
 *
 * Centralizes creation of:
 *  - AncProcessorFacade (platform-specific impl: MultiBand on Android, Ios stub on iOS)
 *  - AudioEngine / platform audio (via platform extensions on the actual; common creation limited by platform deps)
 *  - Future: managers, pipelines, etc for scoped sessions (instead of always GlobalAncSessionContext).
 *
 * Why: reduces direct `= MultiBandANCProcessor(...)` and `= AudioEngine(...)` scattered.
 * Enables test doubles / per-session scoping (see comments in TierManager, ANCService).
 *
 * Current: manual new via factory (no container).
 * Future Koin / manual DI comments:
 *   // Koin: factory { (ctx: AncSessionContext) -> AncSessionFactory(ctx) }
 *   // or single { AncSessionFactory() }
 *   // assisted: AudioEngine(..., processor = get<AncProcessorFacade>())
 *   // manual: val factory = appContainer.ancSessionFactory
 *
 * Usage:
 *   val factory = AncSessionFactory(sessionContext)
 *   val proc = factory.createAncProcessor(sr, buf, tier)
 *   // AudioEngine via platform: (android) factory.createAudioEngine(ctx, ...)  [extra member on android actual]
 *
 * iOS: uses IosAncProcessorFacade pass-through; AudioEngine ios stub via IosAncSessionFactory if added.
 *
 * Keep Android compiling: actuals provided for androidMain + iosMain.
 */
expect class AncSessionFactory(
    sessionContext: AncSessionContext = GlobalAncSessionContext
) {
    fun createAncProcessor(
        sampleRate: Int,
        bufferSize: Int,
        initialTier: UserTier = UserTier.STANDARD
    ): AncProcessorFacade

    // NOTE: create for AudioEngine / platform audio lives in actual impls only
    // (due to platform ctor params like Context on Android). See android actual + ios skeleton.
}
