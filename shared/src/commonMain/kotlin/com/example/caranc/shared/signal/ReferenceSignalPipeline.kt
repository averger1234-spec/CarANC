package com.example.caranc.shared.signal

import androidx.annotation.Keep

@Keep
data class ReferencePipelineMetrics(
    val aecErleDb: Float = 0f,
    val mediaSubtracted: Float = 0f,
    val mediaCorrelation: Float = 0f,
    val mediaActiveFilterLen: Int = 0,
    val mediaMuStep: Float = 0f,
    val mediaAdaptationActive: Boolean = false,
    val engineRpm: Float = 0f,
    val engineRpmValid: Boolean = false,
    val engineFundamentalHz: Float = 0f,
    val playbackActive: Boolean = false
)

@Keep
class ReferenceSignalPipeline(
    private val sampleRate: Int
) {
    private val aec = AcousticEchoCanceller(filterLength = 96)
    private val mediaSubtractor = MediaReferenceSubtractor(filterLength = 128)  // 強化：使用更長 filter 提升音樂扣除能力
    private val engineHarmonic = EngineHarmonicGenerator(sampleRate)

    private var musicActive = false
    private var callActive = false
    private var playbackEnergyEma = 0f

    // Perf note: preprocessBlock allocates ShortArray per block (called every 64 samples ~1.5ms @44k).
    // Quick win: reuse buffer (size fixed to PROCESSING_READ_SIZE=64 in ANCService path) to reduce allocations.
    // (Caller in ANCService can also reuse preprocessed ref; future: pass output buffer in.)
    private var preprocessedReuse = ShortArray(64)

    val metrics = ReferencePipelineMetrics()

    private var lastMetrics = ReferencePipelineMetrics()

    @Keep
    fun setContext(musicActive: Boolean, callActive: Boolean) {
        this.musicActive = musicActive
        this.callActive = callActive
    }

    @Keep
    fun setEngineRpm(rpm: Float, valid: Boolean) {
        engineHarmonic.setRpm(rpm, valid)
    }

    @Keep
    fun preprocessBlock(
        micInput: ShortArray,
        size: Int,
        playbackRef: ShortArray?,
        playbackSize: Int,
        lastAntiNoise: ShortArray?
    ): ShortArray {
        // Use reuse buffer when size matches (common case 64); fallback new only for unusual sizes. Reduces alloc in hot preprocess path.
        val output = if (size == preprocessedReuse.size) preprocessedReuse else ShortArray(size)
        var playbackSum = 0.0

        for (i in 0 until size) {
            val mic = micInput[i] / 32768.0f
            val playback = if (playbackRef != null && i < playbackSize) {
                playbackRef[i] / 32768.0f
            } else {
                0f
            }
            val antiNoise = if (lastAntiNoise != null && i < lastAntiNoise.size) {
                lastAntiNoise[i] / 32768.0f
            } else {
                0f
            }

            playbackSum += playback * playback

            val afterAec = aec.processSample(mic, playback, antiNoise)
            val afterMedia = mediaSubtractor.processSample(
                micSample = afterAec,
                playbackSample = playback,
                musicActive = musicActive
            )

            output[i] = (afterMedia * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        }

        val playbackRms = kotlin.math.sqrt(playbackSum / size.coerceAtLeast(1)).toFloat()
        playbackEnergyEma = 0.95f * playbackEnergyEma + 0.05f * playbackRms

        lastMetrics = ReferencePipelineMetrics(
            aecErleDb = aec.lastErleDb,
            mediaSubtracted = mediaSubtractor.lastSubtracted,
            mediaCorrelation = mediaSubtractor.lastCorrelation,
            mediaActiveFilterLen = mediaSubtractor.lastActiveFilterLength,
            mediaMuStep = mediaSubtractor.lastMuStep,
            mediaAdaptationActive = mediaSubtractor.adaptationActive,
            engineRpm = engineHarmonic.rpm,
            engineRpmValid = engineHarmonic.valid,
            engineFundamentalHz = engineHarmonic.fundamentalHz,
            playbackActive = playbackEnergyEma > 0.001f
        )
        return output
    }

    @Keep
    fun engineBlendWeight(idleMode: Boolean): Float = engineHarmonic.blendWeight(idleMode)

    @Keep
    fun engineReferenceSample(): Float = engineHarmonic.nextSample()

    @Keep
    fun snapshotMetrics(): ReferencePipelineMetrics = lastMetrics

    @Keep
    fun reset() {
        aec.reset()
        mediaSubtractor.reset()
        playbackEnergyEma = 0f
        lastMetrics = ReferencePipelineMetrics()
    }
}