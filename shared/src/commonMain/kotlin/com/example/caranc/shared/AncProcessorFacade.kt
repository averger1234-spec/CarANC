package com.example.caranc.shared

import androidx.annotation.Keep
import com.example.caranc.shared.latency.LatencyBandLimits
import com.example.caranc.shared.model.CabinTransferModel
import com.example.caranc.shared.model.NoiseBandClassification

@Keep
interface AncProcessorFacade : AudioProcessor {
    fun updateSecondaryPath(model: FloatArray)
    fun setAcousticDelay(samples: Int)
    fun getAcousticDelaySamples(): Int
    fun setVehicleSpeed(speedKmh: Float, valid: Boolean)
    fun setProcessingMode(mode: AncProcessingMode)
    fun getProcessingMode(): AncProcessingMode
    fun updateTier(tier: UserTier)
    fun registerBlockEnergy(rms: Float): Boolean
    fun isWeightUpdateFrozen(): Boolean
    fun adjustMu(newMu: Float)
    fun finishLearning()
    fun applyCabinModel(model: CabinTransferModel)
    fun applyClassifierResult(result: NoiseBandClassification)
    fun setCallActive(active: Boolean)
    fun setSirenOverride(active: Boolean, gainScale: Float = 0.05f)
    fun setEngineRpm(rpm: Float, valid: Boolean)
    fun isSirenOverrideActive(): Boolean
    fun getMimoZoneCount(): Int
    fun setEstimatedLatencyMs(latencyMs: Float)
    fun getLatencyBandLimits(): LatencyBandLimits

    // CYCLE3_EXTRA: expose low-band LMS profiling counters (from inner BandFxLms) for AudioEngine extended timing/monitoring.
    // lmsUpdateCount: actual weight updates performed (not just calls).
    // lmsProcessCalls: total invocations into low LMS process path.
    // Used to feed perfMetrics via sessionContext + conditional logs.
    fun getLowLmsUpdateCount(): Long
    fun getLowLmsProcessCalls(): Long
    fun getLastLmsPfx(): Float   // last power norm used in mu calc (debug/profiling)

    // CYCLE3_EXTRA for low band extra (Fdaf/Multirate)
    fun getFdafLmsUpdateCount(): Long = 0L
    fun getMultirateDecimUpdateCount(): Long = 0L

    // For music mode low freq ANC option
    fun setMusicLowAncEnabled(enabled: Boolean)
}