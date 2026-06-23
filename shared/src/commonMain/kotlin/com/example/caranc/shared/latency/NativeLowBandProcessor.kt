package com.example.caranc.shared.latency

import androidx.annotation.Keep

/**
 * CYCLE3_EXTRA: Native low-freq path prototype.
 *
 * Expect/actual bridge for low-band (rumble <~250Hz) processing offload.
 * Goal: move hot LMS (FdafLowBandProcessor / MultirateLowBandFxLms + lowBand BandFxLms)
 * to native (C++/JNI on Android; Accelerate/vDSP or similar on iOS future) for lower CPU in release.
 *
 * Current: low freq path in MultiBandANCProcessor uses:
 *   multirateLow.processSample(...) { lowBand.processSample(...) } + fdafLow.push(lowSample)
 * (See MultiBandANCProcessor ~300 and notes.)
 *
 * This proto provides drop-in hook point. Not yet active in hot path (stub returns 0f / delegates).
 * Future: switch lowOut computation to native when isNativeAvailable() && feature gate.
 *
 * Profiling tie-in: native impl can also bump lms counters exposed from processor.
 *
 * @see FdafLowBandProcessor (overlap-save NLMS candidate for native port)
 * @see MultirateLowBandFxLms (decim FXLMS wrapper)
 */
@Keep
class NativeLowBandProcessor() {
    /**
     * Process one (decimated) low band sample (CYCLE3_EXTRA proto: always no-op for now).
     * Signature matches the one used by Multirate + BandFxLms.
     * Returns 0 (kotlin path in MultiBand handles low band).
     */
    fun processLowBand(
        decimatedSample: Float,
        muScale: Float,
        freezeUpdates: Boolean,
        errorSample: Float = decimatedSample
    ): Float = 0f

    /** Reset (no-op). */
    fun reset() {}

    companion object {
        /** Proto: always false (native not active). */
        fun isNativeAvailable(): Boolean = false
    }
}
