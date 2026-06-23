package com.example.caranc.shared.service

/**
 * P2: Platform audio stubs via expect/actual.
 * Prepares for iOS skeleton + extraction of audio I/O from AudioEngine in future.
 *
 * Android actuals: typealias to real (no behavior change yet; AudioEngine continues direct use of android.media for now).
 * iOS actuals: simple no-op classes.
 *
 * Future: refactor AudioEngine to use PlatformAudioRecorder/Track interfaces for full cross platform.
 * Add comments for DI: these can be provided via factory or DI container.
 */
expect class PlatformAudioRecorder
expect class PlatformAudioTrack
