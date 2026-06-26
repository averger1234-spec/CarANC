package com.example.caranc

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.caranc.shared.AncSessionLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object TestLogExporter {

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
     * 儲存最新 log 到公開可見的「下載 / CarANC_Logs」資料夾。
     * 這樣可以用手機檔案總管或 Google Drive App 直接找到並上傳，減少手動挑選步驟。
     * 路徑通常是 /storage/emulated/0/Download/CarANC_Logs/anc_session_....log
     * （或 Android/data/.../Download 視 Android 版本與權限而定）
     */
    fun saveLatestLogToDownloads(context: Context): String? {
        AncSessionLogger.init(context)
        val logFile = AncSessionLogger.getLatestLogFile() ?: return null
        if (!logFile.exists() || logFile.length() == 0L) return null

        // 優先嘗試公開 Downloads（方便 Drive 同步與檔案總管）
        val baseDir = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } ?: context.getExternalFilesDir(null) ?: return null

        val targetDir = File(baseDir, "CarANC_Logs").apply { mkdirs() }
        val destFile = File(targetDir, logFile.name)

        return try {
            FileInputStream(logFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            // fallback: 複製到 app 外部 files/Download
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() } ?: return null
            val fallbackFile = File(fallbackDir, logFile.name)
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
}