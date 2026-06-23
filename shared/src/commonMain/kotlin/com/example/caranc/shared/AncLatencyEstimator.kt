package com.example.caranc.shared

data class LatencyBreakdown(
    val recordBufferMs: Float,
    val trackBufferMs: Float,
    val processingBlockMs: Float,
    val acousticDelayMs: Float,
    val frameworkMarginMs: Float
) {
    val totalMs: Float =
        recordBufferMs + trackBufferMs + processingBlockMs + acousticDelayMs + frameworkMarginMs
}

object AncLatencyEstimator {

    const val DEFAULT_FRAMEWORK_MARGIN_MS = 35f

    fun maxCancelFrequencyHz(totalLatencyMs: Float): Float =
        (1000f / (4f * totalLatencyMs.coerceIn(15f, 400f))).coerceIn(35f, 350f)

    fun estimate(
        sampleRate: Int,
        recordBufferSamples: Int,
        trackBufferSamples: Int,
        readSize: Int,
        acousticDelaySamples: Int,
        frameworkMarginMs: Float = DEFAULT_FRAMEWORK_MARGIN_MS
    ): LatencyBreakdown {
        require(sampleRate > 0)
        val msPerSample = 1000f / sampleRate
        val recordBufferMs = recordBufferSamples.coerceAtLeast(0) * msPerSample
        val trackBufferMs = trackBufferSamples.coerceAtLeast(0) * msPerSample
        val processingBlockMs = readSize * msPerSample
        val acousticDelayMs = acousticDelaySamples * msPerSample
        return LatencyBreakdown(
            recordBufferMs = recordBufferMs,
            trackBufferMs = trackBufferMs,
            processingBlockMs = processingBlockMs,
            acousticDelayMs = acousticDelayMs,
            frameworkMarginMs = frameworkMarginMs
        )
    }

    fun estimate(
        sampleRate: Int,
        halBufferSamples: Int,
        readSize: Int,
        acousticDelaySamples: Int,
        frameworkMarginMs: Float = DEFAULT_FRAMEWORK_MARGIN_MS
    ): LatencyBreakdown = estimate(
        sampleRate = sampleRate,
        recordBufferSamples = halBufferSamples,
        trackBufferSamples = halBufferSamples,
        readSize = readSize,
        acousticDelaySamples = acousticDelaySamples,
        frameworkMarginMs = frameworkMarginMs
    )
}