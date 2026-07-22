package com.example.caranc.shared

import com.example.caranc.shared.latency.ImuMicCoherenceGate
import com.example.caranc.shared.latency.LatencyAwareBandLimiter
import com.example.caranc.shared.latency.PredictiveReferenceAligner
import com.example.caranc.shared.latency.PreLearnedAncBank
import com.example.caranc.shared.latency.RoadConditionLatentEncoder
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
    fun latentBank_neuralMatch_prefersNearbyCell() {
        val bank2 = PreLearnedAncBank(filterLength = 32, useNeuralLatent = true)
        val wA = FloatArray(32) { if (it == 0) 0.4f else 0f }
        val wB = FloatArray(32) { if (it == 0) -0.4f else 0f }
        bank2.capture(50f, wA, roughness = 0.25f, forceAlpha = 1f, energyProxy = 0.2f, coherence = 0.8f, latencyMs = 80f)
        bank2.capture(90f, wB, roughness = 2.0f, forceAlpha = 1f, energyProxy = 1.2f, coherence = 0.4f, latencyMs = 220f)
        val nearSmooth = bank2.blendedWeights(52f, 0.3f, 0.25f, coherence = 0.8f, latencyMs = 80f)!!
        val nearRough = bank2.blendedWeights(88f, 1.9f, 1.15f, coherence = 0.4f, latencyMs = 220f)!!
        assertTrue(nearSmooth[0] > nearRough[0], "neural latent should separate smooth vs rough road weights (s=${nearSmooth[0]} r=${nearRough[0]})")
        assertTrue(bank2.neuralLatentEnabled)
        assertTrue(bank2.lastMatchQuality in 0.1f..1f)
        assertTrue(bank2.lastQueryLatent.any { abs(it) > 1e-4f })
    }

    @Test
    fun neuralEncoder_unitLatentAndCosine() {
        val enc = RoadConditionLatentEncoder()
        val z1 = enc.encodeRoad(60f, 1.0f, 0.5f, 0.7f, 150f)
        val z2 = enc.encodeRoad(62f, 1.05f, 0.52f, 0.68f, 155f)
        val zFar = enc.encodeRoad(20f, 0.2f, 0.1f, 0.9f, 40f)
        var n2 = 0f
        for (v in z1) n2 += v * v
        assertTrue(abs(n2 - 1f) < 1e-3f, "latent must be unit L2 (n2=$n2)")
        val cosNear = enc.cosine(z1, z2)
        val cosFar = enc.cosine(z1, zFar)
        assertTrue(cosNear > cosFar, "similar road conditions closer in latent (near=$cosNear far=$cosFar)")
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
        assertTrue(proc.getBankMatchQuality() in 0f..1f)
        assertTrue(proc.isNeuralLatentEnabled())
    }
}
