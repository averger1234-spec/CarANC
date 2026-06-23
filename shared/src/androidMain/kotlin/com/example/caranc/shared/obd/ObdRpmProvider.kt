package com.example.caranc.shared.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.caranc.shared.AncTestPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class RpmSnapshot(
    val rpm: Float = 0f,
    val valid: Boolean = false,
    val source: String = "none"
)

/**
 * OBD RPM 提供者：優先讀 ELM327 Bluetooth (auto scan/discovery for ELM/OBD named devices + manual addr),
 * 否則使用手動測試 RPM。Cycle3 P2: added scanning/connecting/connected/fallback states + phase logs.
 */
class ObdRpmProvider(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var pollJob: Job? = null
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    private var snapshot = RpmSnapshot()

    /**
     * Improved state machine for Cycle3 P2 auto-scan:
     * IDLE -> MANUAL | SCANNING -> CONNECTING -> CONNECTED | FALLBACK
     */
    private enum class ObdState { IDLE, MANUAL, SCANNING, CONNECTING, CONNECTED, FALLBACK }

    @Volatile
    private var obdState = ObdState.IDLE

    fun start(): RpmSnapshot {
        stop()
        val manual = AncTestPreferences.getManualTestRpm(context)
        if (manual > 0f) {
            snapshot = RpmSnapshot(rpm = manual, valid = true, source = "manual_test")
            obdState = ObdState.MANUAL
            Log.i(TAG, "phase: manual_test_rpm=$manual")
            return snapshot
        }

        val address = AncTestPreferences.getObdDeviceAddress(context)
        pollJob = scope.launch(Dispatchers.IO) {
            try {
                var connected = false
                if (!address.isBlank()) {
                    // Keep existing manual address path
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "phase: no_bluetooth_connect_perm (manual addr path)")
                        obdState = ObdState.FALLBACK
                        snapshot = RpmSnapshot(source = "no_bt_perm")
                        return@launch
                    }
                    Log.i(TAG, "phase: manual_address_try $address")
                    obdState = ObdState.CONNECTING
                    snapshot = RpmSnapshot(source = "connecting")
                    connected = connect(address)
                } else {
                    // Auto scan + discovery path (Cycle3 P2)
                    connected = autoDiscoverAndConnect()
                }
                if (!connected) {
                    Log.w(TAG, "phase: obd_connect_failed_fallback")
                    obdState = ObdState.FALLBACK
                    snapshot = RpmSnapshot(source = "fallback")
                    // Fallback to manual RPM path (if caller has set it post-start; or use snapshot valid=false for engine fallback)
                    return@launch
                }
                obdState = ObdState.CONNECTED
                Log.i(TAG, "phase: connected_polling")
                while (isActive) {
                    try {
                        val rpm = queryRpm()
                        snapshot = if (rpm > 0f) {
                            RpmSnapshot(rpm = rpm, valid = true, source = "elm327")
                        } else {
                            RpmSnapshot(source = "elm327_invalid")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "OBD poll exception (non-fatal): ${e.message}")
                        snapshot = RpmSnapshot(source = "elm327_error")
                    }
                    delay(1000)
                }
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException in OBD BT (missing BLUETOOTH_CONNECT or SCAN at runtime?): ${se.message}. Using fallback, no crash.")
                obdState = ObdState.FALLBACK
                snapshot = RpmSnapshot(source = "bt_security_error")
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error in OBD pollJob (non-fatal): ${e.message}")
                obdState = ObdState.FALLBACK
                snapshot = RpmSnapshot(source = "error")
            }
        }
        return snapshot
    }

    fun currentSnapshot(): RpmSnapshot = snapshot

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        closeSocket()
        obdState = ObdState.IDLE
        snapshot = RpmSnapshot()
        Log.i(TAG, "phase: stopped")
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(address: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "phase: no_bluetooth_connect_perm (in connect)")
            return false
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            Log.w(TAG, "phase: invalid_address $address")
            return false
        }
        return connectToDevice(device)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToDevice(device: BluetoothDevice): Boolean {
        // FIXED: coroutine-friendly, no Thread.sleep blocking - P0 audio stability
        // Now shared by manual address + auto discovered devices
        return try {
            Log.d(TAG, "phase: connecting ${device.name ?: "??"} ${device.address}")
            val spp = device.createRfcommSocketToServiceRecord(SPP_UUID)
            val connected = withTimeoutOrNull(10000L) {
                spp.connect()
                true
            } ?: false
            if (!connected) {
                Log.w(TAG, "ELM327 Bluetooth connect timed out: ${device.address}")
                closeSocket()
                return false
            }
            socket = spp
            input = spp.inputStream
            output = spp.outputStream
            val initOk = withTimeoutOrNull(8000L) {
                initElm()
                true
            } ?: false
            if (!initOk) {
                Log.w(TAG, "ELM327 init timed out or failed: ${device.address}")
                closeSocket()
                return false
            }
            // Persist successful address for future (improves UX after auto discovery)
            AncTestPreferences.setObdDeviceAddress(context, device.address)
            Log.i(TAG, "ELM327 已連線: ${device.address}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ELM327 連線失敗: ${e.message}")
            closeSocket()
            false
        }
    }

    private fun isElmLike(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.uppercase()
        return n.contains("ELM") || n.contains("OBD") || n.contains("OBDII") ||
               n.contains("ELM327") || n.contains("VGATE") || n.contains("KONNWEI") ||
               n.contains("SCANTOOL") || n.contains("CAR DOCTOR")
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private suspend fun autoDiscoverAndConnect(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            if (!hasConnect || !hasScan) {
                Log.w(TAG, "phase: missing_bluetooth_permissions connect=$hasConnect scan=$hasScan , fallback (no crash)")
                obdState = ObdState.FALLBACK
                snapshot = RpmSnapshot(source = "no_bt_perm")
                return false
            }
        }

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.w(TAG, "phase: no_bluetooth_adapter")
            return false
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "phase: bluetooth_disabled")
            return false
        }

        // Prefer bonded/paired devices first (no scan required)
        Log.i(TAG, "phase: scanning_bonded")
        val bonded = adapter.bondedDevices ?: emptySet()
        val elmBonded = bonded.filter { isElmLike(it.name) }
        Log.i(TAG, "phase: bonded_count=${bonded.size} elm_like=${elmBonded.size}")
        for (dev in elmBonded) {
            obdState = ObdState.CONNECTING
            snapshot = RpmSnapshot(source = "connecting")
            Log.i(TAG, "phase: trying_bonded ${dev.name} ${dev.address}")
            if (connectToDevice(dev)) return true
        }

        // Full discovery scan for unpaired ELM327-like
        Log.i(TAG, "phase: start_discovery_scan")
        if (!adapter.startDiscovery()) {
            Log.w(TAG, "phase: startDiscovery_failed")
            return false
        }

        val discovered = mutableListOf<BluetoothDevice>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null && isElmLike(device.name)) {
                            Log.i(TAG, "phase: found_elm_like ${device.name} ${device.address}")
                            if (discovered.none { it.address == device.address }) discovered.add(device)
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d(TAG, "phase: discovery_finished")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        // Register with compat for SDK>=33
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "phase: register_receiver_failed ${e.message}")
            adapter.cancelDiscovery()
            return false
        }

        try {
            // Wait up to ~12s for classic discovery (collect matches)
            val results = withTimeoutOrNull(12000L) {
                var waited = 0
                while (isActive && adapter.isDiscovering && waited < 12000) {
                    delay(300)
                    waited += 300
                    if (discovered.isNotEmpty() && waited > 4000) {
                        // early exit after finding + some grace time
                        break
                    }
                }
                discovered.toList()
            } ?: emptyList()

            adapter.cancelDiscovery()

            Log.i(TAG, "phase: scan_complete candidates=${results.size}")
            for (dev in results) {
                obdState = ObdState.CONNECTING
                snapshot = RpmSnapshot(source = "connecting")
                Log.i(TAG, "phase: trying_discovered ${dev.name} ${dev.address}")
                if (connectToDevice(dev)) return true
            }
            return false
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            try { adapter.cancelDiscovery() } catch (_: Exception) {}
        }
    }

    private suspend fun initElm() {
        Log.d(TAG, "initElm: sending AT commands sequentially with delays")
        writeCommand("ATZ\r")
        delay(800)
        writeCommand("ATE0\r")
        delay(200)
        writeCommand("ATSP0\r")
        delay(200)
        Log.d(TAG, "initElm: complete")
    }

    private suspend fun queryRpm(): Float {
        val response = writeCommand("010C\r") ?: return 0f
        val bytes = parseHexPayload(response, expectedService = "41 0C") ?: return 0f
        if (bytes.size < 2) return 0f
        val a = bytes[0]
        val b = bytes[1]
        return ((a * 256) + b) / 4f
    }

    private suspend fun writeCommand(command: String): String? {
        return withTimeoutOrNull(3000L) {
            try {
                val out = output ?: return@withTimeoutOrNull null
                val inp = input ?: return@withTimeoutOrNull null
                out.write(command.toByteArray())
                out.flush()
                delay(350)
                val buffer = ByteArray(256)
                val read = inp.read(buffer)
                if (read <= 0) null else String(buffer, 0, read)
            } catch (e: Exception) {
                Log.w(TAG, "OBD 指令失敗: ${e.message}")
                null
            }
        }
    }

    private fun parseHexPayload(response: String, expectedService: String): List<Int>? {
        val normalized = response.uppercase().replace("\r", " ").replace("\n", " ").trim()
        val markerIndex = normalized.indexOf(expectedService.replace(" ", ""))
        if (markerIndex < 0) return null
        val tail = normalized.substring(markerIndex + expectedService.replace(" ", "").length)
        val tokens = tail.split(Regex("\\s+")).filter { it.length == 2 }
        return tokens.mapNotNull { token ->
            runCatching { token.toInt(16) }.getOrNull()
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }

    companion object {
        private const val TAG = "ObdRpmProvider"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}