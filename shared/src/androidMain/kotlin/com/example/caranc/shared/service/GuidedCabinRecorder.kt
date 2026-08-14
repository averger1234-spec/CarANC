package com.example.caranc.shared.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.example.caranc.shared.AncSessionLogger
import java.io.File

/**
 * Auto cabin mic recording during guided test steps (A/B spectrum ground truth).
 * Files: app filesDir/anc_logs/cabin_{stepId}_{ts}.m4a
 */
object GuidedCabinRecorder {
    private const val TAG = "GuidedCabinRecorder"
    private var recorder: MediaRecorder? = null
    private var currentPath: String? = null
    private var currentStepId: String? = null

    @Synchronized
    fun start(context: Context, stepId: String): String? {
        stop(context, reason = "restart")
        return try {
            val dir = File(context.filesDir, "anc_logs").apply { mkdirs() }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(dir, "cabin_${stepId}_$ts.m4a")
            val mr = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(128000)
            mr.setAudioChannels(1)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            currentPath = file.absolutePath
            currentStepId = stepId
            AncSessionLogger.log(
                phase = "cabin_record_start",
                fields = mapOf(
                    "stepId" to stepId,
                    "path" to file.absolutePath,
                    "note" to "auto_cabin_m4a_for_40_80Hz_A_B"
                )
            )
            Log.i(TAG, "start $stepId -> ${file.name}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            AncSessionLogger.log(
                phase = "cabin_record_error",
                fields = mapOf("stepId" to stepId, "error" to (e.message ?: "?"))
            )
            recorder = null
            currentPath = null
            null
        }
    }

    @Synchronized
    fun stop(context: Context, reason: String = "step_end"): String? {
        val path = currentPath
        val step = currentStepId
        try {
            recorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
        }
        recorder = null
        currentPath = null
        currentStepId = null
        if (path != null) {
            val sz = File(path).length()
            AncSessionLogger.log(
                phase = "cabin_record_stop",
                fields = mapOf(
                    "stepId" to (step ?: ""),
                    "path" to path,
                    "bytes" to sz,
                    "reason" to reason,
                    "note" to "compare_cabin_off_vs_on_40_80Hz"
                )
            )
            Log.i(TAG, "stop $step $sz bytes $path")
            // also copy to public Download if possible (best-effort)
            try {
                val pub = File(
                    context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        ?: context.filesDir,
                    File(path).name
                )
                File(path).copyTo(pub, overwrite = true)
                AncSessionLogger.log(
                    phase = "cabin_record_export",
                    fields = mapOf("path" to pub.absolutePath, "bytes" to pub.length())
                )
            } catch (_: Exception) {
            }
        }
        return path
    }

    fun isRecording(): Boolean = recorder != null
}
