package com.example.caranc.shared.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.caranc.shared.*
import com.example.caranc.shared.commercial.CommercialFeature

class ANCService : LifecycleService() {

    // P0 #1 Split + P2 factory: ANCService is now THIN.
    // Only responsible for: LifecycleService, startForeground + notifications, Android Auto CarConnection observer (for isAAConnected + auto stop),
    // onStartCommand (STOP intent, FG start, safety consent, session logger, create+start AudioEngine via AncSessionFactory), onDestroy (stop engine + cleanup).
    // All audio I/O, ANC processing, calibration (chirp), loop, route, vis/state updates delegated to AudioEngine.
    // sessionContext is injected/passed into the engine.
    // Exact same logs ("ANCService" tag), behavior, AA disconnect stopSelf preserved.
    // See AudioEngine.kt for the extracted god-class logic (was ~1222 LOC here).
    // P2: creation of engine (and internal processor) routed through commonMain AncSessionFactory (light DI, comments for Koin).

    // Obtain managers from provided (here global default) AncSessionContext instead of direct singletons.
    // Scoped context recommended for testing/multi-session.
    private val sessionContext = GlobalAncSessionContext

    private val binder = LocalBinder()
    // P0 #1 + P2: processingJob + all audio/ANC fields moved to AudioEngine (owns AudioRecord/Track, processor via factory, pipeline, calibration, main loop etc.)
    private var audioEngine: AudioEngine? = null

    private var carConnection: CarConnection? = null

    private val notificationId = 101
    private val notificationChannelId = "ANC_SERVICE_CHANNEL_V3"

    inner class LocalBinder : Binder() {
        fun getService(): ANCService = this@ANCService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        AncSessionLogger.init(this)
        setupCarConnection()
    }

    private var isAAConnected = false
    private fun setupCarConnection() {
        carConnection = CarConnection(this)
        carConnection?.type?.observe(this) { connectionType ->
            Log.d("ANCService", "Car Connection Type: $connectionType")
            if (connectionType != CarConnection.CONNECTION_TYPE_NOT_CONNECTED) {
                isAAConnected = true
                Log.i("ANCService", "✅ Android Auto 已連接")
                AncSessionLogger.log(
                    phase = "aa_connected",
                    fields = mapOf("connectionType" to connectionType)
                )
            } else {
                if (isAAConnected) {
                    Log.w("ANCService", "🚫 Android Auto 已斷開 -> 執行自動停止")
                    AncSessionLogger.log(phase = "aa_disconnected")
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // P0 #1 + P2: thin onStartCommand - FG, notif, AA handled here; heavy audio/processing delegated to AudioEngine (created via AncSessionFactory)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        Log.d("ANCService", "Notifications enabled: ${manager.areNotificationsEnabled()}")

        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.w("ANCService", "收到通知欄停止指令")
            stopSelf()
            return START_NOT_STICKY
        }

        // Note: audioManager/route/speed creation moved inside AudioEngine for ownership
        try {
            val notification = createNotification("ANC 正在啟動...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(notificationId, notification, foregroundServiceTypes())
            } else {
                startForeground(notificationId, notification)
            }
            Log.d("ANCService", "startForeground 呼叫成功")
        } catch (e: Exception) {
            Log.e("ANCService", "Foreground error: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (sessionContext.entitlementManager.requiresSafetyConsent()) {
            Log.w("ANCService", "安全聲明尚未接受，停止服務")
            AncSessionLogger.log(phase = "service_denied_safety_consent")
            stopSelf()
            return START_NOT_STICKY
        }

        AncSessionLogger.startSession(AncTestPreferences.getEnvironment(this))
        AncSessionLogger.log(
            phase = "service_start",
            fields = mapOf(
                "subscriptionPlan" to sessionContext.entitlementManager.currentPlan.id,
                "mimoEnabled" to sessionContext.entitlementManager.canUseFeature(CommercialFeature.MIMO_TRIAL)
                // OBD RPM (Bluetooth) removed - manual RPM via test prefs only
            )
        )

        // P0 #1 + P2: create AudioEngine via light AncSessionFactory (in commonMain + android actual).
        // AudioEngine creation now goes through factory for scoped components / future DI (Koin/manual).
        // Factory holds sessionContext; android actual provides the platform createAudioEngine.
        // (processor creation inside engine also uses factory - see AudioEngine.kt)
        val factory = AncSessionFactory(sessionContext)
        audioEngine = factory.createAudioEngine(
            appContext = this,
            sessionContext = sessionContext,
            onUpdateNotification = ::updateNotification,
            lifecycleScope = lifecycleScope,
            isAAConnected = { isAAConnected },
            requestStop = { stopSelf() }
        )
        audioEngine?.start()
        return START_STICKY
    }

    // P0 #1: hasLocationPermission + foregroundServiceTypes kept in (thin) ANCService because they are used only for startForeground type declaration here.
    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasLocationPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }








    override fun onDestroy() {
        // P0 #1 + P2: thin onDestroy - delegate stop/release to AudioEngine (which owns all audio/loop/resources; was created via factory).
        // Then do logger end + state + stopForeground (exact same as before).
        Log.w("ANCService", "ANCService onDestroy called")
        audioEngine?.stop()
        audioEngine = null
        AncSessionLogger.endSession("service_destroyed")
        sessionContext.stateManager.updateState(AncState.Stopped())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        try {
            Log.d("ANCService", "Updating notification: $text")
            val notification = createNotification(text)
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.e("ANCService", "Failed to update notification: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun createNotification(contentText: String): Notification {
        val channelName = "ANC Operational Status"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(notificationChannelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "CarANC 降噪狀態監測"
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager.createNotificationChannel(channel)

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ANCService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("CarANC 降噪已啟動")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        Log.d("ANCService", "Notification object created successfully")
        return builder.build()
    }

    companion object {
        // P0 #1: Only ACTION_STOP kept here (thin service); all audio consts/loop consts now owned exclusively inside AudioEngine.
        private const val ACTION_STOP_SERVICE = "com.example.caranc.STOP_SERVICE"
    }
}