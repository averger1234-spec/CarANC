package com.example.caranc.shared.signal

import androidx.annotation.Keep
import kotlin.math.sqrt

/**
 * 簡化 NLMS AEC：以播放參考 + 反噪輸出參考消除麥克風中的迴聲成分。
 */
@Keep
class AcousticEchoCanceller(
    private val filterLength: Int = 96
) {
    private val maxLength = 256
    private val bufferMask = 511
    private val wPlayback = FloatArray(maxLength) { 0f }
    private val wAntiNoise = FloatArray(maxLength) { 0f }
    private val playbackBuf = FloatArray(512) { 0f }
    private val antiNoiseBuf = FloatArray(512) { 0f }
    private var bufferIndex = 0
    private val mu = 0.12f
    private val leakage = 0.9995f

    var lastEchoEstimate = 0f
        private set
    var lastErleDb = 0f
        private set

    @Keep
    fun processSample(
        micSample: Float,
        playbackSample: Float,
        antiNoiseSample: Float
    ): Float {
        playbackBuf[bufferIndex] = playbackSample
        antiNoiseBuf[bufferIndex] = antiNoiseSample

        var echoEstimate = 0f
        for (j in 0 until filterLength.coerceAtMost(maxLength)) {
            val idx = (bufferIndex - j) and bufferMask
            echoEstimate += wPlayback[j] * playbackBuf[idx]
            echoEstimate += wAntiNoise[j] * antiNoiseBuf[idx]
        }
        lastEchoEstimate = echoEstimate

        val error = micSample - echoEstimate
        val refEnergy = playbackSample * playbackSample + antiNoiseSample * antiNoiseSample + 1e-6f
        val step = mu / refEnergy

        for (j in 0 until filterLength.coerceAtMost(maxLength)) {
            val idx = (bufferIndex - j) and bufferMask
            wPlayback[j] = (wPlayback[j] * leakage + step * error * playbackBuf[idx]).coerceIn(-1.5f, 1.5f)
            wAntiNoise[j] = (wAntiNoise[j] * leakage + step * error * antiNoiseBuf[idx]).coerceIn(-1.5f, 1.5f)
        }

        val micEnergy = micSample * micSample
        val errEnergy = error * error
        lastErleDb = if (errEnergy > 1e-9f && micEnergy > 1e-9f) {
            10f * kotlin.math.log10((micEnergy / errEnergy).coerceIn(1f, 1000f))
        } else {
            0f
        }

        bufferIndex = (bufferIndex + 1) and bufferMask
        return error
    }

    @Keep
    fun reset() {
        wPlayback.fill(0f)
        wAntiNoise.fill(0f)
        playbackBuf.fill(0f)
        antiNoiseBuf.fill(0f)
        bufferIndex = 0
        lastEchoEstimate = 0f
        lastErleDb = 0f
    }
}