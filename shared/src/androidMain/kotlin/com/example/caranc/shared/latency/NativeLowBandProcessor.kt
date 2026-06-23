package com.example.caranc.shared.latency

import android.util.Log
import androidx.annotation.Keep

/**
 * Android actual for NativeLowBandProcessor (CYCLE3_EXTRA native low-freq proto).
 *
 * Simple JNI stub:
 * - Declares native methods.
 * - loadLibrary on init (lib name "caranc_lowband" or "anc_low" -- see CMake).
 * - For prototype: if load fails or native not wired, falls back to no-op (0 output).
 * - isNativeAvailable() reflects load success.
 *
 * JNI naming: Java_com_example_caranc_shared_latency_NativeLowBandProcessor_...
 * (or use RegisterNatives in C++ for flexibility).
 *
 * Build integration: See notes added to shared/build.gradle.kts (externalNativeBuild/cmake section commented for proto phase).
 * To activate full:
 *   1. Create src/androidMain/cpp/CMakeLists.txt + .cpp (prototype files provided)
 *   2. Uncomment externalNativeBuild + ndk in shared android {} block.
 *   3. gradlew :shared:assemble  (will invoke cmake via ndk)
 *   4. Ensure NDK installed in Android Studio / sdk.
 *
 * C++ impl notes (in companion .cpp):
 *   - Implement FIR LMS or freq-domain (overlap-save/save via FFT) for low band.
 *   - Reuse fixed-size buffers (no malloc in hot path, like current kotlin push reuse).
 *   - Use NEON intrinsics or auto-vec for LMS inner loops (weight update + dot).
 *   - Decimation handled outside (pass already decimated); or impl inside.
 *   - Expose counters via JNI or side-channel for processor profiling.
 *   - Threading: called from AudioEngine audio thread -- keep RT safe, no locks/allocs.
 *
 * Current: always delegates to kotlin path in MultiBand (native used only for future opt-in + perf tests).
 */
// CYCLE3_EXTRA: NativeLowBandProcessor proto (now provided from commonMain as no-op class).
// This file kept for documentation / future full JNI + CMake activation (see comments below and in shared/build.gradle.kts).
// The actual JNI impl would live here (loadLibrary, external native funs, etc.) when enabling native build.
// For now, the common class provides the no-op, and MultiBand uses it (or skips via isNativeAvailable false).

/*
@Keep
actual class NativeLowBandProcessor() {

    actual companion object {
        private const val TAG = "NativeLowBand"
        private const val LIB = "caranc_lowband"  // matches CMake output target

        @Volatile
        private var nativeLoaded = false

        init {
            try {
                System.loadLibrary(LIB)
                nativeLoaded = true
                Log.i(TAG, "Native low-band lib loaded: $LIB (CYCLE3_EXTRA proto)")
            } catch (t: Throwable) {
                nativeLoaded = false
                Log.w(TAG, "Native low-band stub (no $LIB): ${t.message}. Using kotlin LMS fallback for low freq.")
            }
        }

        actual fun isNativeAvailable(): Boolean = nativeLoaded
    }

    actual fun processLowBand(
        decimatedSample: Float,
        muScale: Float,
        freezeUpdates: Boolean,
        errorSample: Float
    ): Float {
        if (!isNativeAvailable()) {
            return 0f
        }
        return try {
            nativeProcessLowBand(decimatedSample, muScale, freezeUpdates, errorSample)
        } catch (t: Throwable) {
            Log.w(TAG, "JNI call failed, fallback 0: ${t.message}")
            0f
        }
    }

    actual fun reset() {
        if (isNativeAvailable()) {
            try { nativeReset() } catch (_: Throwable) {}
        }
    }

    private external fun nativeProcessLowBand(
        sample: Float,
        muScale: Float,
        freeze: Boolean,
        error: Float
    ): Float

    private external fun nativeReset()
}
*/
