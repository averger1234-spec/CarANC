package com.example.caranc.shared.service

import android.content.Context
import com.example.caranc.shared.*
import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext
import kotlinx.coroutines.CoroutineScope

/**
 * Android actual for AncSessionFactory (P2 light DI).
 *
 * Implements common expect.
 * Provides createAncProcessor -> MultiBandANCProcessor (the real DSP).
 *
 * Also exposes extra android-only createAudioEngine(...) so that AudioEngine creation
 * now goes through the factory (from call sites in this platform like ANCService).
 *
 * The extra member is ONLY in android actual file (visible to androidMain code).
 * Common callers see only the declared expect surface.
 *
 * Future DI: this actual can be constructed by DI providing the sessionContext.
 */
actual class AncSessionFactory actual constructor(
    private val sessionContext: AncSessionContext
) {
    actual fun createAncProcessor(
        sampleRate: Int,
        bufferSize: Int,
        initialTier: UserTier
    ): AncProcessorFacade {
        // CYCLE3_P2: pass sessionContext so MultiBand (and any internals like wiener) can use scoped
        // stateManager/analyzers/models from context (instead of global singletons).
        return MultiBandANCProcessor(
            sampleRate = sampleRate,
            bufferSize = bufferSize,
            initialTier = initialTier,
            sessionContext = sessionContext
        )
    }

    /**
     * Android-specific: AudioEngine creation now routed through (this) factory.
     * Matches the previous direct construction in ANCService.
     * Can be extended to wire additional scoped components (e.g. pre-created processor/pipeline).
     *
     * Call: val engine = AncSessionFactory(sessionContext).createAudioEngine(...)
     */
    fun createAudioEngine(
        appContext: Context,
        sessionContext: AncSessionContext,
        onUpdateNotification: (String) -> Unit,
        lifecycleScope: CoroutineScope,
        isAAConnected: () -> Boolean,
        requestStop: () -> Unit = {}
    ): AudioEngine {
        // Creation of the (android) AudioEngine goes through factory as of P2.
        // sessionContext provided for scoping (currently Global, but factory holds it).
        return AudioEngine(
            appContext = appContext,
            sessionContext = sessionContext,
            onUpdateNotification = onUpdateNotification,
            lifecycleScope = lifecycleScope,
            isAAConnected = isAAConnected,
            requestStop = requestStop
        )
    }
}

// Note: default param on actual ctor matches the expect declaration so AncSessionFactory() works from common-style call sites.

