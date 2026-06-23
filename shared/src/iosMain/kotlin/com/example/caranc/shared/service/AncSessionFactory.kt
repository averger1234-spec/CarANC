package com.example.caranc.shared.service

import com.example.caranc.shared.*
import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext

/**
 * iOS actual for AncSessionFactory (P2 light DI skeleton).
 *
 * createAncProcessor -> returns the IosAncProcessorFacade pass-through stub.
 *
 * AudioEngine creation: not in common expect surface (platform specific ctors).
 * For iOS, future iOS service code can use:
 *   val factory = AncSessionFactory(sessionContext)
 *   val engine = IosAudioEngineFactory(factory).create(...) or direct AudioEngine stub.
 *
 * See ios AudioEngine.kt and IosAncProcessorFacade.kt .
 */
actual class AncSessionFactory actual constructor(
    private val sessionContext: AncSessionContext
) {
    actual fun createAncProcessor(
        sampleRate: Int,
        bufferSize: Int,
        initialTier: UserTier
    ): AncProcessorFacade {
        // CYCLE3_P2: pass sessionContext (for signature parity with Android MultiBandANCProcessor;
        // iOS stub ignores but allows future use of scoped managers in real iOS port).
        return IosAncProcessorFacade(
            sampleRate = sampleRate,
            bufferSize = bufferSize,
            initialTier = initialTier,
            sessionContext = sessionContext
        )
    }

    // iOS-specific extra (not visible from common): if needed for ios audio engine creation wiring.
    // Example for future:
    // fun createAudioEngine(sessionContext: AncSessionContext): AudioEngine = AudioEngine(sessionContext)
}
