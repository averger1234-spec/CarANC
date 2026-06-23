package com.example.caranc.shared.latency

import androidx.annotation.Keep
import com.example.caranc.shared.RoadNoiseReferenceModel
import kotlin.math.PI
import kotlin.math.sin

/**
 * GPS 車速驅動的路噪 feedforward Wiener 近似（低頻合成反噪）。
 *
 * CYCLE3_EXTRA: supports scoped RoadNoiseReferenceModel via ctor (default global instance).
 * - Created inside MultiBandANCProcessor (which receives sessionContext); wiener now gets context's road ref.
 * - Allows full mock of thresholds/blend in tests by providing custom RoadNoiseReferenceModel to context/ctor.
 */
@Keep
class RoadNoiseWienerBank(
    private val sampleRate: Int,
    private val roadRef: RoadNoiseReferenceModel = RoadNoiseReferenceModel()
) {
    private val twoPi = 2.0 * PI
    private var phaseA = 0.0
    private var phaseB = 0.0
    private var phaseC = 0.0

    private var speedKmh = 0f
    private var speedValid = false
    private var envelopeEma = 0f

    var lastOutput = 0f
        private set

    @Keep
    fun setSpeed(speedKmh: Float, valid: Boolean) {
        this.speedKmh = speedKmh.coerceAtLeast(0f)
        this.speedValid = valid
    }

    @Keep
    fun feedforwardSample(micLowBand: Float): Float {
        if (!speedValid || speedKmh < RoadNoiseReferenceModel.DRIVING_SPEED_THRESHOLD_KMH) {
            lastOutput = 0f
            return 0f
        }

        envelopeEma = 0.992f * envelopeEma + 0.008f * kotlin.math.abs(micLowBand)
        val energy = roadRef.roadEnergyScale(speedKmh)
        val fLow = (35f + speedKmh * 0.35f).coerceIn(35f, 120f)
        val fMid = (fLow * 2.2f).coerceIn(80f, 220f)
        val fHigh = (fLow * 3.8f).coerceIn(140f, 320f)

        phaseA += twoPi * fLow / sampleRate
        phaseB += twoPi * fMid / sampleRate
        phaseC += twoPi * fHigh / sampleRate
        if (phaseA > twoPi) phaseA -= twoPi
        if (phaseB > twoPi) phaseB -= twoPi
        if (phaseC > twoPi) phaseC -= twoPi

        val gain = (0.08f + 0.22f * energy) * (0.5f + envelopeEma * 4f)
        val synth = gain * (
            0.55f * sin(phaseA).toFloat() +
                0.30f * sin(phaseB).toFloat() +
                0.15f * sin(phaseC).toFloat()
            )
        lastOutput = (-synth).coerceIn(-0.7f, 0.7f)
        return lastOutput
    }

    @Keep
    fun blendGain(): Float {
        if (!speedValid || speedKmh < RoadNoiseReferenceModel.DRIVING_SPEED_THRESHOLD_KMH) return 0f
        return roadRef.roadBlendWeight(speedKmh, speedValid).coerceIn(0f, 0.5f) * 1.2f
    }
}