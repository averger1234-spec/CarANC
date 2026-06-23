package com.example.caranc.shared.service

/**
 * iOS actual for P2 audio stubs (no-op).
 * Placeholder for future real impl using e.g. AVAudioEngine, AudioUnit, or high-level iOS audio session/recorder.
 * Used via expect in common for cross-platform audio record/track abstraction.
 */
actual class PlatformAudioRecorder {
    // iOS no-op stub. No recording performed.
    // Future: init with AVAudioEngine input, buffer callbacks, etc.
}

actual class PlatformAudioTrack {
    // iOS no-op stub. No playback performed.
    // Future: output via AVAudioPlayerNode or AudioQueue, mix with anti-noise.
}
