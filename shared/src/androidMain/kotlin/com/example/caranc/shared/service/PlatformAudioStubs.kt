package com.example.caranc.shared.service

import android.media.AudioRecord as AndroidAudioRecord
import android.media.AudioTrack as AndroidAudioTrack

/**
 * Android actual for P2 audio stubs.
 * Uses typealias so existing direct usage in AudioEngine is unaffected.
 * When we introduce common audio interface, these provide the impl.
 */
actual typealias PlatformAudioRecorder = AndroidAudioRecord
actual typealias PlatformAudioTrack = AndroidAudioTrack
