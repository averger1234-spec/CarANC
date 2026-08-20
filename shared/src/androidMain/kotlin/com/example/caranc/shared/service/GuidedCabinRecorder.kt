package com.example.caranc.shared.service

import android.content.Context
import android.util.Log
import com.example.caranc.shared.AncSessionLogger
import java.io.File
import java.io.RandomAccessFile

/**
 * Auto cabin recording during guided test steps (A/B spectrum ground truth).
 *
 * Dual-end alignment with iOS: write PCM WAV from the **same AudioRecord samples**
 * the ANC loop uses. A second MediaRecorder MIC session fights AudioRecord and
 * often fails or records a different path.
 *
 * Files: app filesDir/anc_logs/cabin_{stepId}_{ts}.wav
 */
object GuidedCabinRecorder {
    private const val TAG = "GuidedCabinRecorder"
    private const val HEADER_BYTES = 44

    private var raf: RandomAccessFile? = null
    private var file: File? = null
    private var currentStepId: String? = null
    private var sampleRate: Int = 44100
    private var frames: Long = 0
    @Volatile private var recording = false
    @Volatile private var engineSampleRate: Int = 44100

    fun setEngineSampleRate(sr: Int) {
        if (sr > 8000) engineSampleRate = sr
    }

    @Synchronized
    fun start(context: Context, stepId: String): String? {
        stop(context, reason = "restart")
        return try {
            val dir = File(context.filesDir, "anc_logs").apply { mkdirs() }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val out = File(dir, "cabin_${stepId}_$ts.wav")
            val r = RandomAccessFile(out, "rw")
            r.setLength(0)
            r.write(ByteArray(HEADER_BYTES))
            raf = r
            file = out
            currentStepId = stepId
            sampleRate = engineSampleRate
            frames = 0
            recording = true
            AncSessionLogger.log(
                phase = "cabin_record_start",
                fields = mapOf(
                    "stepId" to stepId,
                    "path" to out.absolutePath,
                    "format" to "wav_int16_mono",
                    "sampleRate" to sampleRate,
                    "note" to "anc_audiorecord_pcm_same_mic_as_dsp"
                )
            )
            Log.i(TAG, "start $stepId -> ${out.name}")
            out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            AncSessionLogger.log(
                phase = "cabin_record_error",
                fields = mapOf("stepId" to stepId, "error" to (e.message ?: "?"))
            )
            closeQuiet()
            recording = false
            null
        }
    }

    fun append(samples: ShortArray, size: Int) {
        if (!recording || size <= 0) return
        val n = minOf(size, samples.size)
        val bytes = ByteArray(n * 2)
        var b = 0
        for (i in 0 until n) {
            val v = samples[i].toInt()
            bytes[b++] = (v and 0xFF).toByte()
            bytes[b++] = ((v shr 8) and 0xFF).toByte()
        }
        synchronized(this) {
            if (!recording) return
            try {
                raf?.write(bytes)
                frames += n
            } catch (e: Exception) {
                Log.w(TAG, "append: ${e.message}")
            }
        }
    }

    @Synchronized
    fun stop(context: Context, reason: String = "step_end"): String? {
        val path = file?.absolutePath
        val step = currentStepId
        val nFrames = frames
        val sr = sampleRate
        val outFile = file
        try {
            raf?.let { rewriteWavHeader(it, sr, nFrames) }
        } catch (e: Exception) {
            Log.w(TAG, "header: ${e.message}")
        }
        closeQuiet()
        recording = false
        currentStepId = null
        frames = 0
        file = null
        if (path != null && outFile != null) {
            val sz = outFile.length()
            AncSessionLogger.log(
                phase = "cabin_record_stop",
                fields = mapOf(
                    "stepId" to (step ?: ""),
                    "path" to path,
                    "bytes" to sz,
                    "frames" to nFrames,
                    "durationSec" to (nFrames.toDouble() / sr.coerceAtLeast(1)),
                    "reason" to reason,
                    "note" to "compare_cabin_off_vs_on_40_80Hz"
                )
            )
            Log.i(TAG, "stop $step $sz bytes $path")
            try {
                val pub = File(
                    context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        ?: context.filesDir,
                    outFile.name
                )
                outFile.copyTo(pub, overwrite = true)
                AncSessionLogger.log(
                    phase = "cabin_record_export",
                    fields = mapOf("path" to pub.absolutePath, "bytes" to pub.length())
                )
            } catch (_: Exception) {
            }
        }
        return path
    }

    fun isRecording(): Boolean = recording

    private fun closeQuiet() {
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
    }

    private fun rewriteWavHeader(raf: RandomAccessFile, sampleRate: Int, frames: Long) {
        val dataSize = (frames * 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val sr = sampleRate.coerceAtLeast(8000)
        raf.seek(0)
        raf.writeBytes("RIFF")
        writeIntLe(raf, 36 + dataSize)
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        writeIntLe(raf, 16)
        writeShortLe(raf, 1)
        writeShortLe(raf, 1)
        writeIntLe(raf, sr)
        writeIntLe(raf, sr * 2)
        writeShortLe(raf, 2)
        writeShortLe(raf, 16)
        raf.writeBytes("data")
        writeIntLe(raf, dataSize)
    }

    private fun writeIntLe(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF)
        raf.write((v shr 8) and 0xFF)
        raf.write((v shr 16) and 0xFF)
        raf.write((v shr 24) and 0xFF)
    }

    private fun writeShortLe(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF)
        raf.write((v shr 8) and 0xFF)
    }
}
