package com.example.caranc.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the application state (ANC mode, viz spectra/dB, speed, latency, dominant band), shared between
 * the Android Service (AudioEngine/ANCService) and the UI/ViewModel.
 *
 * CYCLE3_P2: Refactored from `object` singleton to regular class (with no-arg ctor) to enable true scoping.
 * - Instantiate directly for tests: AncStateManager()
 * - Preferred: obtain via AncSessionContext.stateManager (supports injection of test-double / per-session instance)
 * - Global compat: GlobalAncSessionContext.stateManager (or legacy direct if not yet migrated)
 *
 * Previously direct global object access everywhere; now context provides it (delegates or holds instance).
 * Callers updated to use sessionContext.stateManager.* (no new direct singleton accesses).
 * This + similar for analyzers/models enables mockable/scoped DSP for Cycle3 testing.
 *
 * SCOPED STATE NOTE (P2 start): global singleton path kept for service<->UI compat during transition.
 * Prefer scoped instance via AncSessionContext for test isolation / multi-session.
 */
class AncStateManager {

    // 修正：初始狀態應該是 Stopped，而不是 Calibrating
    private val _state = MutableStateFlow<AncState>(AncState.Stopped())
    val state: StateFlow<AncState> = _state.asStateFlow()

    // --- Visualization Data ---
    val noiseSpectrum = MutableStateFlow(FloatArray(64))
    val cancelledSpectrum = MutableStateFlow(FloatArray(64))
    val rawDb = MutableStateFlow(0.0f)
    val cancelledDb = MutableStateFlow(0.0f)
    val vehicleSpeedKmh = MutableStateFlow(0.0f)
    val vehicleSpeedValid = MutableStateFlow(false)
    val dominantNoiseBand = MutableStateFlow("MIXED")

    val estimatedLatencyMs = MutableStateFlow(0f)
    val maxCancelFrequencyHz = MutableStateFlow(0f)
    val latencyMidEnabled = MutableStateFlow(false)
    val latencyHighEnabled = MutableStateFlow(false)
    val latencyRecordMs = MutableStateFlow(0f)
    val latencyTrackMs = MutableStateFlow(0f)
    val latencyBlockMs = MutableStateFlow(0f)

    fun updateState(newState: AncState) {
        _state.value = newState
    }

    fun updateVehicleSpeed(speedKmh: Float, valid: Boolean) {
        vehicleSpeedKmh.value = speedKmh
        vehicleSpeedValid.value = valid
    }

    fun updateDominantNoiseBand(band: String) {
        dominantNoiseBand.value = band
    }

    fun updateLatencyMonitor(
        estimatedMs: Float,
        maxCancelHz: Float,
        midEnabled: Boolean,
        highEnabled: Boolean,
        recordMs: Float = 0f,
        trackMs: Float = 0f,
        blockMs: Float = 0f
    ) {
        estimatedLatencyMs.value = estimatedMs
        maxCancelFrequencyHz.value = maxCancelHz
        latencyMidEnabled.value = midEnabled
        latencyHighEnabled.value = highEnabled
        latencyRecordMs.value = recordMs
        latencyTrackMs.value = trackMs
        latencyBlockMs.value = blockMs
    }

    fun updateVisualization(noise: FloatArray, cancelled: FloatArray, rDb: Float, cDb: Float) {
        noiseSpectrum.value = noise
        cancelledSpectrum.value = cancelled
        rawDb.value = rDb
        cancelledDb.value = cDb
    }
}
