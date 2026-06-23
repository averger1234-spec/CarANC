package com.example.caranc.shared

import android.content.Context
import android.os.Build
import com.example.caranc.shared.commercial.EntitlementManager
import com.example.caranc.shared.commercial.ProductCatalog
import com.example.caranc.shared.commercial.initEntitlementStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

object AncSessionLogger {

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val sessionActive = AtomicBoolean(false)
    private var appContext: Context? = null
    private var currentLogFile: File? = null

    // NON-BLOCKING REFACTOR: audio thread safe
    // All synchronous file.appendText / writeText + synchronized(this) removed from hot path.
    // Now: Channel<String> buffers the log lines (header + jsonl).
    // Single dedicated writer coroutine on Dispatchers.IO does the actual disk IO (append/write).
    // Public methods (log, startSession, endSession) use trySend (non-suspending, never blocks caller).
    // This prevents blocking the real-time audio processing thread (ANCService audio IO coroutine, running_snapshot every ~2s + event logs).
    // Prevents audio glitches, jitter, high latency variance.
    // Writer lifecycle tied to session (recreated on start, closed on endSession).
    // Existing API, JSONL, header, phases, isEnabled via AncTestPreferences unchanged. Callers untouched.
    // File ops moved entirely to writer. Uses java.io.File as before.

    private var logChannel = Channel<String>(Channel.UNLIMITED)
    private var writerJob: Job? = null
    private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        initEntitlementStore(context)
    }

    fun isEnabled(): Boolean {
        val context = appContext ?: return false
        return AncTestPreferences.isLoggingEnabled(context)
    }

    fun startSession(environment: AncTestEnvironment) {
        val context = appContext ?: return
        if (!isEnabled()) return

        val logDir = getLogDirectory(context) ?: return
        logDir.mkdirs()

        val fileName = "anc_session_${dateFormat.format(Date())}.log"
        currentLogFile = File(logDir, fileName)
        sessionActive.set(true)

        // launch single bg writer coroutine (lifecycle tied to session)
        logChannel = Channel<String>(Channel.UNLIMITED)
        launchWriterIfNeeded()

        // send header via channel (non-blocking); writer does writeText
        val header = buildString {
            appendLine("# CarANC Session Log")
            appendLine("# format=jsonl")
            appendLine("# vehicle=${environment.vehicleModel}")
            appendLine("# scenario=${environment.scenario}")
            appendLine("# phonePlacement=${environment.phonePlacement}")
            appendLine("# connectionType=${environment.connectionType}")
            appendLine("# ---")
        }
        logChannel.trySend(header)

        log(
            phase = "session_start",
            fields = mapOf(
                "vehicleModel" to environment.vehicleModel,
                "scenario" to environment.scenario,
                "phonePlacement" to environment.phonePlacement,
                "connectionType" to environment.connectionType,
                "deviceModel" to Build.MODEL,
                "deviceManufacturer" to Build.MANUFACTURER,
                "androidRelease" to Build.VERSION.RELEASE,
                "sdkInt" to Build.VERSION.SDK_INT,
                "productName" to ProductCatalog.PRODUCT_NAME,
                "subscriptionPlan" to EntitlementManager.currentPlan.id,
                "subscriptionLabel" to EntitlementManager.currentPlan.displayName,
                "safetyConsentAccepted" to EntitlementManager.snapshot.value.safetyConsentAccepted
            )
        )
    }

    private fun launchWriterIfNeeded() {
        if (writerJob?.isActive != true) {
            writerJob = loggerScope.launch {
                for (content in logChannel) {
                    val file = currentLogFile ?: continue
                    try {
                        if (content.startsWith("# CarANC Session Log")) {
                            // header for new session: use writeText (replaces content)
                            file.writeText(content)
                        } else {
                            // regular log line (pre-formatted + separator)
                            file.appendText(content)
                        }
                    } catch (_: Exception) {
                        // swallow: logger must never impact real-time audio path
                    }
                }
            }
        }
    }

    fun log(phase: String, fields: Map<String, Any?> = emptyMap()) {
        if (!sessionActive.get() || !isEnabled()) return
        val file = currentLogFile ?: return

        val line = AncSessionLogFormatter.formatLine(
            phase = phase,
            timestampMs = System.currentTimeMillis(),
            fields = fields
        )

        // NON-BLOCKING: send to channel (trySend never blocks). Writer on IO does append.
        logChannel.trySend(line + System.lineSeparator())
    }

    fun endSession(reason: String) {
        if (!sessionActive.get()) return
        log(phase = "session_end", fields = mapOf("reason" to reason))
        sessionActive.set(false)

        // proper close handling on endSession
        logChannel.close()
        writerJob?.cancel()
        writerJob = null
        // channel + writer recreated on next startSession
    }

    fun getLatestLogFile(): File? {
        val context = appContext ?: return currentLogFile
        val logs = getLogDirectory(context)?.listFiles()
            ?.filter { it.isFile && it.name.startsWith("anc_session_") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        return logs.firstOrNull() ?: currentLogFile
    }

    fun getLogDirectory(context: Context? = appContext): File? {
        val ctx = context ?: return null
        return File(ctx.filesDir, "anc_logs")
    }
}