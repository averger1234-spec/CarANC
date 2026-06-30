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
    val playbackActive: Boolean = false,
    // IMU aux feedforward (Road Preview) metrics for NVH map / predictive.
    // rumbleAuxEma: smoothed vibration proxy; rumbleAuxScale: adaptive mix strength used this block.
    val rumbleAuxEma: Float = 0f,
    val rumbleAuxScale: Float = 0f,
    // P1: how well music was suppressed (0-1). Low value -> trigger conservative mode to protect music.
    // High value + rumble context -> safe to do selective rumble enhancement (amplify road noise residue).
    val musicSuppressionQuality: Float = 0f,
    // "音樂能量 vs 路噪能量比" guard from subtractor (high = music dominates energy, more conservative)
    val musicRoadEnergyRatio: Float = 0f
)

@Keep
class ReferenceSignalPipeline(
    private val sampleRate: Int
) {
    private val aec = AcousticEchoCanceller(filterLength = 96)
    private val mediaSubtractor = MediaReferenceSubtractor(filterLength = 256)  // P1: raised to 256 (was 128) per log analysis for longer AA music path; class max now supports 512
    private val engineHarmonic = EngineHarmonicGenerator(sampleRate)

    private var musicActive = false
    private var callActive = false
    private var playbackEnergyEma = 0f

    // IMU rumble aux feedforward state for Road Preview / NVH hybrid (mic + structural vibration).
    // EMA for smooth proxy; adaptive scale for preview (higher on rough detected).
    private var rumbleAccelEma = 0f
    private var lastRumbleScale = 0.0008f

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
        lastAntiNoise: ShortArray?,
        rumbleAccel: Float = 0f,  // simple IMU aux ref for rumble feedforward (vibration proxy mixed into low freq residue)
        musicDominantRumble: Boolean = false,  // first-principles: when music dominant, boost IMU rumble ref weight (immune to music), reduce reliance on mic-based afterMedia for low rumble
        suppressionQuality: Float = 1f  // for dynamic IMU boost: lower suppression -> more aggressive IMU ref to compensate
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

            // IMU + mic hybrid feedforward (Road Preview / NVH Waze moat).
            // Structural vibration proxy (phone IMU, immune to acoustic feedback) mixed as aux ref into residue.
            // EMA smooths jitter; adaptive scale for preview (boost mix on high rumble/speed for pre-emptive low-freq cancel).
            // This is the core for crowdsourced dynamic road noise map + predictive preload of S(z)/VSS.
            // Future: use rumbleEma / variance to key local "segment profile" or request cloud S(z) preload.
            rumbleAccelEma = 0.85f * rumbleAccelEma + 0.15f * rumbleAccel.coerceAtLeast(0f)
            val baseScale = 0.0015f  // P3: increased from 0.0008 for more aggressive IMU aux ref mix into reference (structural rumble feedforward stronger for low band prediction)
            // Adaptive: higher mix when vibration strong (preview rough road) or high speed context (caller speed not passed, infer via accel).
            val adapt = (1f + (rumbleAccelEma * 0.6f).coerceAtMost(1.2f))
            var rumbleScale = (baseScale * adapt).coerceIn(0.0005f, 0.005f)
            // First-principles: in MUSIC_DOMINANT_RUMBLE, boost IMU rumble ref even more (immune to music/latency issues), de-emphasize afterMedia (mic-based which has music bleed).
            // Dynamic: when suppressionQuality low (<0.4), extra aggressive boost (1.3-1.5x more) so IMU becomes even more dominant.
            if (musicDominantRumble) {
                // 06-30 feedback: avoid over-conserv. Strong IMU rumble ref boost only when clear rumble energy (accelEma proxy) vs possible residual music.
                val hasClearRumble = rumbleAccelEma > 0.35f
                var boost = if (hasClearRumble) 2.5f else 1.1f
                if (suppressionQuality < 0.4f && hasClearRumble) {
                    boost *= (1.0f + (0.4f - suppressionQuality) * 1.25f).coerceIn(1.0f, 1.5f)
                }
                rumbleScale *= boost
            }
            lastRumbleScale = rumbleScale
            val rumbleRef = rumbleAccelEma * rumbleScale
            val afterRumble = afterMedia - rumbleRef   // aux ref subtraction (feedforward style, hybrid with mic error)

            output[i] = (afterRumble * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
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
            playbackActive = playbackEnergyEma > 0.001f,
            rumbleAuxEma = rumbleAccelEma,
            rumbleAuxScale = lastRumbleScale,
            musicSuppressionQuality = mediaSubtractor.lastSuppressionQuality,
            musicRoadEnergyRatio = mediaSubtractor.lastMusicRoadEnergyRatio
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