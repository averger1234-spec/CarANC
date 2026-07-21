package com.example.caranc.shared.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * #8: Low-latency **local (non-AA)** audio path.
 *
 * True AAudio exclusive is NDK-only; on modern Android, AudioTrack/AudioRecord with
 * PERFORMANCE_MODE_LOW_LATENCY is routed through the AAudio path internally.
 * Use this for garage / phone-speaker algorithm validation — never for AA projection.
 *
 * AA mode must keep classic AudioTrack + remote_submix (see AudioEngine).
 */
object LocalLowLatencyAudio {
    private const val TAG = "LocalLowLatencyAudio"

    data class Config(
        val sampleRate: Int,
        val recordBufferBytes: Int,
        val trackBufferBytes: Int,
        val audioSource: Int,
        val trackAttributes: AudioAttributes,
        val backendLabel: String
    )

    /**
     * Build buffer sizes + attributes optimized for local speaker validation.
     * Caps buffers aggressively using PROPERTY_OUTPUT_FRAMES_PER_BUFFER when available.
     */
    fun plan(
        audioManager: AudioManager,
        sampleRate: Int,
        framesPerBuffer: Int,
        minRecord: Int,
        minTrack: Int
    ): Config {
        val frameBytes = 2 // mono 16-bit
        val targetFrames = framesPerBuffer.coerceIn(64, 256)
        val targetBytes = (targetFrames * frameBytes * 4).coerceIn(512, 4096)

        val recordBytes = minRecord.coerceAtLeast(targetBytes).coerceAtMost(8192)
        val trackBytes = minTrack.coerceAtLeast(targetBytes).coerceAtMost(8192)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        Log.i(
            TAG,
            "LOCAL_AAUDIO_PATH: sr=$sampleRate framesPerBuf=$framesPerBuffer " +
                "recBytes=$recordBytes trackBytes=$trackBytes (garage/local only)"
        )

        return Config(
            sampleRate = sampleRate,
            recordBufferBytes = recordBytes,
            trackBufferBytes = trackBytes,
            audioSource = source,
            trackAttributes = attrs,
            backendLabel = "AAUDIO_LIKE_LOCAL_LOW_LATENCY"
        )
    }

    fun buildTrack(
        config: Config,
        sampleRate: Int = config.sampleRate
    ): AudioTrack {
        val builder = AudioTrack.Builder()
            .setAudioAttributes(config.trackAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(config.trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    fun buildRecord(config: Config, sampleRate: Int = config.sampleRate): AudioRecord {
        var source = config.audioSource
        var record = AudioRecord(
            source,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            config.recordBufferBytes
        )
        if (record.state != AudioRecord.STATE_INITIALIZED &&
            source == MediaRecorder.AudioSource.UNPROCESSED
        ) {
            record.release()
            source = MediaRecorder.AudioSource.VOICE_RECOGNITION
            record = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                config.recordBufferBytes
            )
        }
        return record
    }
}
