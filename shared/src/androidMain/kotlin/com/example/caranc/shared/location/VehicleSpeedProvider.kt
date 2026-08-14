package com.example.caranc.shared.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.caranc.shared.VehicleSpeedSnapshot
import com.example.caranc.shared.model.VehicleSpeedFusion
import com.example.caranc.shared.model.VehicleSpeedFusionState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt
import kotlin.math.roundToInt

class VehicleSpeedProvider(context: Context) {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private var linearAccelMag = 0f
    private var linearAccelX = 0f
    private var linearAccelY = 0f
    private var linearAccelZ = 0f
    private val accelListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent?) {
            if (event?.sensor?.type == android.hardware.Sensor.TYPE_LINEAR_ACCELERATION) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                linearAccelX = x
                linearAccelY = y
                linearAccelZ = z
                linearAccelMag = kotlin.math.sqrt(x * x + y * y + z * z)
                // GPS 稀疏時仍用 IMU 推進 fusion（hold / imu_proxy）
                if (running) {
                    republishFusion(gpsSpeedKmh = lastRawGpsKmh, gpsValid = lastRawGpsValid, sourceHint = lastGpsSource)
                }
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    private val _snapshot = MutableStateFlow(VehicleSpeedSnapshot.invalid())
    val snapshot: StateFlow<VehicleSpeedSnapshot> = _snapshot.asStateFlow()

    private var lastLocation: Location? = null
    private var smoothedSpeedKmh = 0f
    private var running = false
    private var fusionState = VehicleSpeedFusionState()
    private var lastRawGpsKmh = 0f
    private var lastRawGpsValid = false
    private var lastGpsSource = "none"
    /** 融合後 source：gps | gps_hold | imu_proxy | none */
    @Volatile var lastFusionSource: String = "none"
        private set
    @Volatile var lastHoldAgeSec: Float = -1f
        private set

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        UPDATE_INTERVAL_MS
    )
        .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
        .setWaitForAccurateLocation(false)
        .build()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            publish(location, source = "fused")
        }
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun currentSnapshot(): VehicleSpeedSnapshot = _snapshot.value

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return hasPermission()
        running = true

        // IMU 一律啟動：無 GPS 時仍可 imu_proxy / 掉線 hold 後續
        val accelSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        if (accelSensor != null) {
            // FASTEST for structural FF ref waveform (GAME ~50Hz too slow for 40–80 boom)
            sensorManager.registerListener(
                accelListener,
                accelSensor,
                android.hardware.SensorManager.SENSOR_DELAY_FASTEST
            )
            Log.i(TAG, "IMU accel FASTEST registered (axes + mag for RNC-style FF)")
        }

        if (!hasPermission()) {
            Log.w(TAG, "GPS 車速：無定位權限 → IMU 代車速備用（imu_proxy）")
            republishFusion(gpsSpeedKmh = 0f, gpsValid = false, sourceHint = "none")
            return false
        }

        fusedClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) publish(location, source = "last_known")
        }

        Log.i(TAG, "GPS 車速追蹤已啟動（含 gps_hold / imu_proxy fusion）")
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        fusedClient.removeLocationUpdates(callback)
        sensorManager.unregisterListener(accelListener)
        linearAccelMag = 0f
        _snapshot.value = VehicleSpeedSnapshot.invalid()
        lastLocation = null
        smoothedSpeedKmh = 0f
        fusionState = VehicleSpeedFusionState()
        lastRawGpsKmh = 0f
        lastRawGpsValid = false
        lastFusionSource = "none"
        lastHoldAgeSec = -1f
        Log.i(TAG, "GPS 車速追蹤已停止 (IMU listener unregistered)")
    }

    private fun publish(location: Location, source: String) {
        val hasGpsSpeed = location.hasSpeed() && location.speed >= 0f
        val speedKmh = resolveSpeedKmh(location)
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        val gpsValid = isSpeedValid(speedKmh, accuracy, hasGpsSpeed)

        lastLocation = location
        lastRawGpsKmh = if (gpsValid) speedKmh else 0f
        lastRawGpsValid = gpsValid
        lastGpsSource = if (hasGpsSpeed) "$source:gps_speed" else source

        republishFusion(gpsSpeedKmh = speedKmh, gpsValid = gpsValid, sourceHint = lastGpsSource, location = location)
    }

    private fun republishFusion(
        gpsSpeedKmh: Float,
        gpsValid: Boolean,
        sourceHint: String,
        location: Location? = lastLocation
    ) {
        val now = System.currentTimeMillis()
        val (fused, nextState) = VehicleSpeedFusion.fuse(
            gpsSpeedKmh = gpsSpeedKmh,
            gpsValid = gpsValid,
            accelMag = linearAccelMag,
            nowMs = now,
            state = fusionState
        )
        fusionState = nextState
        lastFusionSource = fused.source
        lastHoldAgeSec = fused.holdAgeSec
        smoothedSpeedKmh = fused.speedKmh

        val cLat = if (location != null && location.hasAccuracy() && location.accuracy < 200f) {
            (location.latitude * 1000).roundToInt() / 1000f
        } else 0f
        val cLon = if (location != null && location.hasAccuracy() && location.accuracy < 200f) {
            (location.longitude * 1000).roundToInt() / 1000f
        } else 0f
        val accuracy = location?.let { if (it.hasAccuracy()) it.accuracy else 999f } ?: 999f
        val rough = linearAccelMag.coerceAtLeast(0f)
        // valid：DSP 可用（含 hold / imu_proxy）；log 用 source 區分品質
        val srcLabel = when (fused.source) {
            "gps" -> sourceHint
            "gps_hold" -> "gps_hold"
            "imu_proxy" -> "imu_proxy"
            else -> "none"
        }
        _snapshot.value = VehicleSpeedSnapshot(
            speedKmh = fused.speedKmh,
            valid = fused.validForDsp,
            accuracyMeters = accuracy,
            source = srcLabel,
            linearAccelMagnitude = linearAccelMag,
            accelSource = if (linearAccelMag > 0f || linearAccelX != 0f) "linear_accel" else "none",
            linearAccelX = linearAccelX,
            linearAccelY = linearAccelY,
            linearAccelZ = linearAccelZ,
            coarseLat = cLat,
            coarseLon = cLon,
            roughness = rough
        )
    }

    private fun resolveSpeedKmh(location: Location): Float {
        if (location.hasSpeed() && location.speed >= 0f) {
            return location.speed * 3.6f
        }

        val previous = lastLocation ?: return 0f
        val dtSeconds = (location.time - previous.time) / 1000f
        if (dtSeconds <= 0.2f) return smoothedSpeedKmh

        val dx = location.latitude - previous.latitude
        val dy = location.longitude - previous.longitude
        val meters = sqrt(dx * dx + dy * dy) * 111_320.0
        return (meters / dtSeconds * 3.6).toFloat().coerceAtLeast(0f)
    }

    private fun isSpeedValid(speedKmh: Float, accuracyMeters: Float, hasGpsSpeed: Boolean): Boolean {
        if (speedKmh < 0f) return false
        // 裝置直接回報 GPS 速度時，放寬精度門檻以便行車判斷
        if (hasGpsSpeed) {
            if (speedKmh >= MOVING_SPEED_THRESHOLD_KMH && accuracyMeters <= 120f) return true
            if (speedKmh < MOVING_SPEED_THRESHOLD_KMH && accuracyMeters <= 80f) return true
        }
        if (accuracyMeters > 50f) return false
        if (speedKmh == 0f && accuracyMeters > 25f) return false
        return true
    }

    companion object {
        private const val TAG = "VehicleSpeedProvider"
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val MIN_UPDATE_INTERVAL_MS = 500L
        const val MOVING_SPEED_THRESHOLD_KMH = 5f
    }
}