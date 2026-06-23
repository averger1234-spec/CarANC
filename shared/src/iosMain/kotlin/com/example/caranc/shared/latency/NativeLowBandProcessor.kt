package com.example.caranc.shared.latency

import androidx.annotation.Keep

/**
 * iOS actual for NativeLowBandProcessor (CYCLE3_EXTRA proto stub).
 *
 * No native (JNI) on iOS here; provides safe no-op.
 * Future: could bridge to iOS Accelerate (vDSP) for FFT-LMS low band, or Swift C++ interop.
 * For now: always !available, returns 0 (low path handled by common kotlin impl when used from MultiBand).
 */
// CYCLE3_EXTRA: iOS stub for NativeLowBandProcessor (class now in commonMain as no-op).
// Kept for docs; the common impl provides the no-op for iOS too.

/*
@Keep
actual class NativeLowBandProcessor() {

    actual fun processLowBand(
        decimatedSample: Float,
        muScale: Float,
        freezeUpdates: Boolean,
        errorSample: Float
    ): Float = 0f

    actual fun reset() {
        // no-op on iOS stub
    }

    actual companion object {
        actual fun isNativeAvailable(): Boolean = false
    }
}
*/
