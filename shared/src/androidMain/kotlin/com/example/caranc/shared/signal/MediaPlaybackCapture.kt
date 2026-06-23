package com.example.caranc.shared.signal

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.os.Build
import android.util.Log

/**
 * 擷取車機/系統媒體播放作為參考，供音樂 bypass 與 AEC 使用。
 * 需要 API 29+。API 35+ 若系統移除 AudioAttributes 建構子，需 MediaProjection（尚未整合）。
 */
class MediaPlaybackCapture(
    private val sampleRate: Int,
    private val bufferSize: Int
) {
    private var audioRecord: AudioRecord? = null
    var isAvailable = false
        private set
    var lastStartError: String? = null
        private set

    fun start(): Boolean {
        lastStartError = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastStartError = "requires_api_29"
            Log.w(TAG, "AudioPlaybackCapture 需要 API 29+")
            return false
        }

        val captureConfig = buildCaptureConfiguration() ?: run {
            lastStartError = "capture_config_unavailable"
            Log.w(TAG, "無法建立 AudioPlaybackCaptureConfiguration")
            return false
        }

        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                lastStartError = "invalid_min_buffer_$minBuffer"
                return false
            }
            val recordBuffer = bufferSize.coerceAtLeast(minBuffer) * 2

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(recordBuffer)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                lastStartError = "record_not_initialized"
                Log.w(TAG, "MediaPlaybackCapture 初始化失敗")
                false
            } else {
                record.startRecording()
                audioRecord = record
                isAvailable = true
                Log.i(TAG, "MediaPlaybackCapture 已啟動")
                true
            }
        } catch (e: SecurityException) {
            lastStartError = "security_${e.message}"
            Log.e(TAG, "MediaPlaybackCapture 權限不足: ${e.message}")
            false
        } catch (e: Exception) {
            lastStartError = "${e.javaClass.simpleName}:${e.message}"
            Log.e(TAG, "MediaPlaybackCapture 啟動失敗: ${e.message}")
            false
        }
    }

    private fun buildCaptureConfiguration(): AudioPlaybackCaptureConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val captureAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        return try {
            val builderClass = AudioPlaybackCaptureConfiguration.Builder::class.java
            val builder = try {
                builderClass.getConstructor(AudioAttributes::class.java).newInstance(captureAttrs)
            } catch (_: NoSuchMethodException) {
                Log.w(
                    TAG,
                    "API ${Build.VERSION.SDK_INT} 僅支援 MediaProjection 擷取，暫未整合"
                )
                return null
            }

            val addUsage = builderClass.getMethod("addMatchingUsage", Int::class.javaPrimitiveType)
            MATCHING_USAGES.forEach { usage -> addUsage.invoke(builder, usage) }
            builderClass.getMethod("build").invoke(builder) as AudioPlaybackCaptureConfiguration
        } catch (e: Exception) {
            Log.w(TAG, "建立 capture config 失敗: ${e.message}")
            null
        }
    }

    fun read(buffer: ShortArray, size: Int): Int {
        val record = audioRecord ?: return 0
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return 0
        return record.read(buffer, 0, size).coerceAtLeast(0)
    }

    fun stop() {
        audioRecord?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            } catch (_: Exception) {
            }
            release()
        }
        audioRecord = null
        isAvailable = false
    }

    companion object {
        private const val TAG = "MediaPlaybackCapture"

        private val MATCHING_USAGES = intArrayOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        )
    }
}