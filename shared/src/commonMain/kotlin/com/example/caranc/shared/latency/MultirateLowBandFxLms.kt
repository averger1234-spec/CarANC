package com.example.caranc.shared.latency

import androidx.annotation.Keep

/**
 * 低頻 4x 降採樣 FXLMS 包裝：等效更長時間窗、更低運算延遲。
 * CYCLE3_EXTRA: counters + native low-freq proto candidate (see NativeLowBandProcessor + build notes).
 */
@Keep
class MultirateLowBandFxLms(
    private val decimation: Int = 4
) {
    private var accumulator = 0f
    private var sampleCount = 0
    private var heldOutput = 0f
    private var subSampleIndex = 0

    // CYCLE3_EXTRA: profiling counters for low-freq multirate path (native candidate).
    var multirateProcessCalls = 0L
        private set
    var multirateDecimUpdates = 0L
        private set

    @Keep
    fun processSample(
        sample: Float,
        subProcessor: (Float) -> Float
    ): Float {
        multirateProcessCalls++
        accumulator += sample
        sampleCount++
        if (sampleCount >= decimation) {
            heldOutput = subProcessor(accumulator / decimation)
            multirateDecimUpdates++
            accumulator = 0f
            sampleCount = 0
            subSampleIndex = 0
        }
        val frac = subSampleIndex.toFloat() / decimation.coerceAtLeast(1)
        subSampleIndex = (subSampleIndex + 1).coerceAtMost(decimation)
        return heldOutput * (1f - frac * 0.15f)
    }

    @Keep
    fun reset() {
        accumulator = 0f
        sampleCount = 0
        heldOutput = 0f
        subSampleIndex = 0
        multirateProcessCalls = 0L
        multirateDecimUpdates = 0L
    }
}