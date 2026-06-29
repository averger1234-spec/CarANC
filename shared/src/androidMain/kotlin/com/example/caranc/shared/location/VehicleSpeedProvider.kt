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
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class VehicleSpeedProvider(context: Context) {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private var linearAccelMag = 0f
    private val accelListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent?) {
            if (event?.sensor?.type == android.hardware.Sensor.TYPE_LINEAR_ACCELERATION) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                linearAccelMag = kotlin.math.sqrt(x * x + y * y + z * z)
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    private val _snapshot = MutableStateFlow(VehicleSpeedSnapshot.invalid())
    val snapshot: StateFlow<VehicleSpeedSnapshot> = _snapshot.asStateFlow()

    private var lastLocation: Location? = null
    private var smoothedSpeedKmh = 0f
    private var running = false

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
        if (!hasPermission()) {
            _snapshot.value = VehicleSpeedSnapshot.invalid()
            Log.w(TAG, "GPS 車速：無定位權限，fallback 純麥克風模式")
            return false
        }

        running = true
        fusedClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) publish(location, source = "last_known")
        }

        // IMU prototype for rumble proxy (linear accel, only logging for data collection in strict protocol runs on 68/國道)
        val accelSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        if (accelSensor != null) {
            sensorManager.registerListener(accelListener, accelSensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "IMU accel listener registered for rumble proxy logging")
        }

        Log.i(TAG, "GPS 車速追蹤已啟動")
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
        Log.i(TAG, "GPS 車速追蹤已停止 (IMU listener unregistered)")
    }

    private fun publish(location: Location, source: String) {
        val hasGpsSpeed = location.hasSpeed() && location.speed >= 0f
        val speedKmh = resolveSpeedKmh(location)
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        val valid = isSpeedValid(speedKmh, accuracy, hasGpsSpeed)

        smoothedSpeedKmh = if (valid) {
            if (smoothedSpeedKmh <= 0f) speedKmh else smoothedSpeedKmh * 0.82f + speedKmh * 0.18f
        } else {
            0f
        }

        lastLocation = location
        _snapshot.value = VehicleSpeedSnapshot(
            speedKmh = smoothedSpeedKmh,
            valid = valid,
            accuracyMeters = accuracy,
            source = if (hasGpsSpeed) "$source:gps_speed" else source,
            linearAccelMagnitude = linearAccelMag,
            accelSource = if (linearAccelMag > 0f) "linear_accel" else "none"
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