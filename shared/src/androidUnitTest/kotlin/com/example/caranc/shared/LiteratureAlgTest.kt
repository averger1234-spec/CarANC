package com.example.caranc.shared

import com.example.caranc.shared.latency.ImuMicCoherenceGate
import com.example.caranc.shared.latency.LatencyAwareBandLimiter
import com.example.caranc.shared.latency.PredictiveReferenceAligner
import com.example.caranc.shared.latency.PreLearnedAncBank
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unit tests for literature-aligned algorithm pieces:
 * secondary-path BW limit, predictive ref, coherence gate, latent bank match.
 */
class LiteratureAlgTest {

    @Test
    fun maxCancelHz_highAaLatency_staysInLowRumbleBand() {
        val hz250 = LatencyAwareBandLimiter.maxCancelFrequencyHz(250f)
        val hz200 = LatencyAwareBandLimiter.maxCancelFrequencyHz(200f)
        val hz50 = LatencyAwareBandLimiter.maxCancelFrequencyHz(50f)
        assertTrue(hz250 in 45f..100f, "250ms AA must only allow very-low cancel (got $hz250)")
        assertTrue(hz200 in 45f..120f, "200ms must be low-rumble only (got $hz200)")
        assertTrue(hz50 > hz250, "lower latency must allow higher maxCancelHz ($hz50 vs $hz250)")
        val lim = LatencyAwareBandLimiter.limits(200f)
        assertTrue(!lim.midEnabled, "mid disabled at 200ms")
        assertTrue(!lim.highEnabled, "high disabled at 200ms")
    }

    @Test
    fun predictiveRef_plantAligned_isBipolarAndStable() {
        val pred = PredictiveReferenceAligner()
        val sr = 44100
        repeat(8000) { i ->
            val x = (0.4 * sin(2.0 * Math.PI * 80.0 / sr * i)).toFloat()
            pred.push(x)
        }
        val d = pred.delayed(100)
        val p = pred.predict(50)
        val aligned = pred.plantAlignedReference(2000, 0.2f)
        assertTrue(abs(d) <= 1.2f && abs(p) <= 1.2f && abs(aligned) <= 1.2f)
        // After long sine, delayed should be non-trivial
        assertTrue(abs(pred.latest()) > 0.05f || abs(d) > 0.02f, "should hold audio energy")
    }

    @Test
    fun coherenceGate_highWhenImuAndMicTogether() {
        val gate = ImuMicCoherenceGate()
        var qCoupled = 0.5f
        repeat(8000) {
            qCoupled = gate.update(absLowMic = 0.35f, imuMag = 1.2f)
        }
        val gate2 = ImuMicCoherenceGate()
        var qBad = 0.5f
        repeat(8000) {
            qBad = gate2.update(absLowMic = 0.02f, imuMag = 3.5f)
        }
        assertTrue(
            qCoupled > qBad + 0.05f,
            "coupled IMU+mic should score higher than vibration-only (c=$qCoupled bad=$qBad)"
        )
    }

    @Test
    fun latentBank_energyMatch_prefersNearbyCell() {
        val bank = PreLearnedAncBank(filterLength = 32)
        val wA = FloatArray(32) { if (it == 0) 0.4f else 0f }
        val wB = FloatArray(32) { if (it == 0) -0.4f else 0f }
        bank.capture(70f, wA, roughness = 1.0f, forceAlpha = 1f, energyProxy = 0.2f)
        bank.capture(70f, wB, roughness = 1.0f, forceAlpha = 1f, energyProxy = 1.2f)
        // Last capture overwrites same cell — use different roughness bins
        val bank2 = PreLearnedAncBank(filterLength = 32)
        bank2.capture(50f, wA, roughness = 0.25f, forceAlpha = 1f, energyProxy = 0.2f)
        bank2.capture(90f, wB, roughness = 2.0f, forceAlpha = 1f, energyProxy = 1.2f)
        val nearSmooth = bank2.blendedWeights(52f, 0.3f, 0.25f)!!
        val nearRough = bank2.blendedWeights(88f, 1.9f, 1.15f)!!
        assertTrue(nearSmooth[0] > 0f, "smooth/low-energy should pull positive prior cell")
        assertTrue(nearRough[0] < 0f, "rough/high-energy should pull negative cell")
        assertTrue(bank2.lastMatchQuality in 0.15f..1f)
    }

    @Test
    fun multiBand_highLat_stillCancelsLowTone_literaturePath() {
        val proc = MultiBandANCProcessor(44100, 256)
        proc.setMeasuredLatencyBreakdown(40f, 170f, 1.3f, 1f, 35f)
        proc.setVehicleSpeed(60f, true)
        proc.setRumbleAccel(1.0f)
        proc.setRoadRoughness(1.0f)
        proc.setProcessingMode(AncProcessingMode.ROAD_NOISE_GPS)
        val n = 2048
        val tone = ShortArray(n) { i ->
            val s = (0.5 * sin(2.0 * Math.PI * 90.0 / 44100.0 * i) * 32767).toInt()
            s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        repeat(30) { proc.process(tone) }
        val out = proc.process(tone)
        val rmsOut = kotlin.math.sqrt(out.map { (it / 32768.0) * (it / 32768.0) }.average())
        assertTrue(rmsOut > 1e-5, "literature high-lat path must still emit anti (rms=$rmsOut)")
        assertTrue(proc.getLatencyStrategy().contains("HIGH_LAT") || proc.getMeasuredLatencyMs() > 180f)
        assertTrue(proc.getImuMicCoherenceQuality() in 0f..1f)
    }
}
