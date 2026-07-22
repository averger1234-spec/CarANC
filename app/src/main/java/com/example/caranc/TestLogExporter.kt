package com.example.caranc

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.caranc.shared.AncSessionLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TestLogExporter {

    private val copyStamp = SimpleDateFormat("HHmmss", Locale.US)

    fun shareLatestLog(context: Context): Boolean {
        val logFile = AncSessionLogger.getLatestLogFile() ?: return false
        if (!logFile.exists() || logFile.length() == 0L) return false

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            logFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CarANC Session Log")
            putExtra(Intent.EXTRA_TEXT, "CarANC 實車測試 log：${logFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "匯出 CarANC Log")
        if (context !is android.app.Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return true
    }

    /**
     * 儲存最新 log 到「下載 / CarANC_Logs」。
     * 每次儲存用 **獨立檔名**（原名 + _saved_HHmmss），避免第二次覆蓋第一次、
     * 或檔案總管只注意到一筆。App 內部 files/anc_logs 仍依 session 各一份。
     */
    fun saveLatestLogToDownloads(context: Context): String? {
        AncSessionLogger.init(context)
        val logFile = AncSessionLogger.getLatestLogFile() ?: return null
        if (!logFile.exists() || logFile.length() == 0L) return null

        val baseDir = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } ?: context.getExternalFilesDir(null) ?: return null

        val targetDir = File(baseDir, "CarANC_Logs").apply { mkdirs() }
        // Unique dest so repeated saves never overwrite a previous copy
        val baseName = logFile.nameWithoutExtension
        val destName = "${baseName}_saved_${copyStamp.format(Date())}.log"
        val destFile = File(targetDir, destName)

        return try {
            FileInputStream(logFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (_: Exception) {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
                ?: return null
            val fallbackFile = File(fallbackDir, destName)
            FileInputStream(logFile).use { input ->
                FileOutputStream(fallbackFile).use { output -> input.copyTo(output) }
            }
            fallbackFile.absolutePath
        }
    }

    fun latestLogFileName(context: Context): String? {
        AncSessionLogger.init(context)
        return AncSessionLogger.getLatestLogFile()?.name
    }

    /** All session logs in app sandbox, newest first (for UI list / debug). */
    fun listSessionLogs(context: Context): List<File> {
        AncSessionLogger.init(context)
        val dir = AncSessionLogger.getLogDirectory(context) ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("anc_session_") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }
}