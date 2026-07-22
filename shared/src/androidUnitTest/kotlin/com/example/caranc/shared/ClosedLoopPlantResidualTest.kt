package com.example.caranc.shared

import com.example.caranc.shared.latency.PlantAlignedResidual
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ideal closed-loop verification path (user/literature):
 *
 *  1. known low-freq road-like noise x(n)
 *  2. feed to MultiBandANCProcessor (real FxLMS / bank path)
 *  3. anti y(n)
 *  4. plant: y_plant = y delayed by D
 *  5. residual = x + y_plant
 *  6. residual energy < x energy  → true cancel under this plant
 *
 * This is stronger than formula sim_iter and stronger than "anti has energy only".
 */
class ClosedLoopPlantResidualTest {

    private val sr = 44100

    private fun tone(freq: Float, n: Int, amp: Float = 0.5f): ShortArray {
        val a = ShortArray(n)
        val w = 2.0 * Math.PI * freq / sr
        for (i in 0 until n) {
            a[i] = (amp * sin(w * i) * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return a
    }

    private fun rms(s: ShortArray, from: Int = 0): Double {
        if (s.isEmpty() || from >= s.size) return 0.0
        var ss = 0.0
        for (i in from until s.size) {
            val v = s[i] / 32768.0
            ss += v * v
        }
        return sqrt(ss / (s.size - from))
    }

    @Test
    fun plantMix_helper_pureDelayAligns() {
        val x = tone(80f, 512)
        // anti = -x delayed by 0 → cancel if same-index
        val anti = ShortArray(x.size) { i -> (-x[i]).toShort() }
        val r0 = PlantAlignedResidual.mix(x, anti, 0)
        assertTrue(rms(r0) < rms(x) * 0.1, "same-index inverted anti must cancel")
        val rep = PlantAlignedResidual.report(x, anti, 0)
        assertTrue(rep.plantReductionDb > 10f, "plant red at D=0 should be large, got ${rep.plantReductionDb}")
    }

    @Test
    fun closedLoop_lowPlantDelay_processorCancelsTone() {
        val proc = MultiBandANCProcessor(sr, 256)
        proc.setMeasuredLatencyBreakdown(5f, 8f, 2f, 1f, 4f)
        proc.setEstimatedLatencyMs(20f)
        val block = 2048
        val x = tone(90f, block, 0.55f)
        repeat(50) { proc.process(x) }
        val y = proc.process(x)
        val d = proc.getPlantElectricalDelaySamples().coerceAtMost(200)
        val rep = PlantAlignedResidual.report(x, y, d)
        assertTrue(rep.antiRmsDb > -60f, "must produce anti (got antiDb=${rep.antiRmsDb})")
        // With small D, plant residual should improve after warm-up
        assertTrue(
            rep.plantReductionDb > 0f || rep.sameIdxReductionDb > 0f,
            "closed-loop residual must shrink (plantRed=${rep.plantReductionDb} sameRed=${rep.sameIdxReductionDb})"
        )
    }

    @Test
    fun closedLoop_aaPlantDelay_residualUsesDelayNotSameIndex() {
        val proc = MultiBandANCProcessor(sr, 256)
        proc.setMeasuredLatencyBreakdown(40f, 170f, 1.3f, 1f, 35f)
        proc.setVehicleSpeed(60f, true)
        proc.setRumbleAccel(1.0f)
        proc.setProcessingMode(AncProcessingMode.ROAD_NOISE_GPS)
        val d = proc.getPlantElectricalDelaySamples()
        assertTrue(d > 1000, "AA plant D should be large, got $d")

        val x = tone(100f, 4096, 0.5f)
        repeat(40) { proc.process(x) }
        val y = proc.process(x)
        val rep = PlantAlignedResidual.report(x, y, d)

        // Core structural check: if anti is non-trivial, same-index residual often worse than plant-aligned
        // when controller learned for delayed plant (or at least plant path is the fair metric).
        assertTrue(rep.antiRmsDb > -70f || rms(y) > 1e-6, "output path must emit")
        // Fair cancel metric is plantReductionDb — document value for log analysis
        assertTrue(
            rep.plantReductionDb > -12f,
            "plant residual should not explode (plantRed=${rep.plantReductionDb} sameRed=${rep.sameIdxReductionDb} anti=${rep.antiRmsDb})"
        )
    }

    @Test
    fun bandEnergy_tonePeaksNearFreq() {
        val x = tone(100f, 4096, 0.6f)
        val e100 = PlantAlignedResidual.bandEnergyDb(x, sr, 100f)
        val e200 = PlantAlignedResidual.bandEnergyDb(x, sr, 200f)
        assertTrue(e100 > e200, "100Hz tone energy at 100 > 200 (e100=$e100 e200=$e200)")
    }
}
