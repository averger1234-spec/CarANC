package com.example.caranc

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.caranc.shared.AncSessionLogger
object TestLogExporter {

    fun shareLatestLog(context: Context): Boolean {
        val logFile = AncSessionLogger.getLatestLogFile() ?: return false
        if (!logFile.exists() || logFile.length() == 0L) return false

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
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

    fun latestLogFileName(context: Context): String? {
        AncSessionLogger.init(context)
        return AncSessionLogger.getLatestLogFile()?.name
    }
}