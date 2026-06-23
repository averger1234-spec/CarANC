package com.example.caranc.shared.service

import com.example.caranc.shared.AncSessionContext
// kotlinx.coroutines.CoroutineScope not needed in iOS stub ctor (platform specific wiring future)

/**
 * iOS skeleton for AudioEngine (P2).
 *
 * No-op stub implementation.
 * Full AudioEngine (with AudioRecord/Track, real-time loop, calibration, MultiBand DSP etc) remains Android-only for now.
 *
 * When iOS target enabled and iOS service layer added (e.g. analogous to ANCService), this provides the entry.
 * Ctor uses minimal common params (unlike android version which takes Context + AA callbacks).
 * Audio I/O will use the PlatformAudio* stubs + future iOS audio session.
 *
 * ANC processor: will be provided via AncSessionFactory (stub pass-through on iOS).
 */
class AudioEngine(
    private val sessionContext: AncSessionContext,
    // Future iOS params e.g. ios view/controller refs, audio session category etc. go here.
) {
    fun start() {
        // no-op for iOS skeleton
        // TODO: iOS: setup AVAudioSession, start engine, wire iOS anc processor facade, run processing loop (DispatchQueue)
    }

    fun stop() {
        // no-op
        // TODO: iOS release audio resources, stop session
    }
}
