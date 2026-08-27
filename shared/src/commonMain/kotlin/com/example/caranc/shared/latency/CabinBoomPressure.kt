package com.example.caranc.shared.latency

import com.example.caranc.shared.Keep
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * **Cabin boom pressure path** — put real low-frequency anti energy on the speakers for 悶.
 *
 * 1.2.11: delay = total AA RTT; no uncontrolled soft-boost.
 * 1.2.14: **openBoom** — when corr unlocked / polarity forced, enforce min LF drive
 * so boomPressureOut is audible (40–120 via dual ~90 Hz LPF). Without this,
 * `y = −delayed·gain` stays ≈0 whenever delayed mic sample is tiny (1.2.13 road FAIL).
 *
 * Mechanism:
 * - Ring-buffer cabin mic low-band sample
 * - Read x(n − D)
 * - Output anti = polarity · (−gain · lowpass(drive))
 * - drive = delayed, or ±energyFloor when openBoom and |delayed| too small
 */
@Keep
class CabinBoomPressure(
    private val sampleRate: Int,
    capacity: Int = 16384
) {
    private val ring = FloatArray(capacity.coerceAtLeast(2048))
    private var write = 0
    private var count = 0

    // Dual ~90 Hz — pass cabin 74–80 Hz (HU often HPF'd 50). Dual pole still
    // knocks 180–350 ~−15 dB; 55 Hz was also burying 74 Hz 共鳴.
    private var lp1 = 0f
    private var lp2 = 0f
    private val lpCoeff: Float =
        (2.0 * kotlin.math.PI * 90.0 / sampleRate).toFloat().coerceIn(0.004f, 0.10f)

    var lastOutput = 0f
        private set
    var lastGain = 0f
        private set
    var lastRms = 0f
        private set
    var lastPeak = 0f
        private set
    var lastDelayUsed = 0
        private set
    /** 1.2.14: true when open-boom energy floor substituted for tiny delayed mic. */
    var lastUsedOpenFloor = false
        private set

    private var sumSq = 0.0
    private var peakAbs = 0f
    private var blockN = 0

    @Keep
    fun push(micLowSample: Float) {
        ring[write % ring.size] = micLowSample.coerceIn(-1.5f, 1.5f)
        write++
        if (count < ring.size) count++
    }

    /**
     * @param openBoom when true (forced polarity / unlocked corr), guarantee min LF pressure
     * @param openScale 1.2.16: 0.25–0.30 for unlocked/short-test; 1.0 when corr locked
     * @param polarity +1 or −1
     */
    @Keep
    fun process(
        plantDelaySamples: Int,
        speedKmh: Float,
        boomPriority: Boolean,
        freeze: Boolean,
        polarity: Float = 1f,
        openBoom: Boolean = false,
        openScale: Float = 1f,
        /** 1.2.39: false when plant residual is adding — no synthetic F0 at wrong D. */
        allowOpenFloor: Boolean = true
    ): Float {
        lastUsedOpenFloor = false
        if (!boomPriority || speedKmh < 16f || count < 8) {
            lastOutput *= 0.92f
            lastGain = 0f
            accumulate(lastOutput)
            return lastOutput
        }
        val d = plantDelaySamples.coerceIn(0, min(count - 1, ring.size - 1))
        val dUse = if (d < sampleRate / 80) (sampleRate / 40).coerceAtMost(count - 1) else d
        lastDelayUsed = dUse
        val idx = (write - 1 - dUse).mod(ring.size)
        val delayed = ring[idx]

        val speedNorm = ((speedKmh - 12f) / 65f).coerceIn(0.20f, 1.35f)
        val scale = openScale.coerceIn(
            com.example.caranc.shared.LiveTuneOverlay.MIN_BOOM_OPEN_SCALE,
            com.example.caranc.shared.LiveTuneOverlay.MAX_BOOM_OPEN_SCALE
        )
        // 1.2.37: louder open floor — 1.2.36 scale=1.0 still boomOut~0.02 / antiE40~−26.
        val energyFloor = when {
            speedKmh >= 50f -> 0.28f
            speedKmh >= 35f -> 0.20f
            else -> 0.12f
        } * scale
        // 1.2.14/1.2.16: open boom min drive (scaled down when corr unlocked / short-test)
        val drive = if (openBoom && allowOpenFloor && abs(delayed) < energyFloor) {
            lastUsedOpenFloor = true
            val s = if (abs(delayed) > 1e-8f) sign(delayed) else 1f
            s * energyFloor
        } else {
            // Do not floor scale at 0.5 — that undoes 1.2.16 short-test 0.25/0.30.
            delayed * (if (openBoom) scale else 1f)
        }
        val energy = abs(drive).coerceIn(
            if (openBoom && allowOpenFloor) energyFloor else 0.02f,
            0.90f
        )
        val gain = (0.90f + 0.70f * speedNorm) * (0.55f + 1.20f * energy)
            .coerceIn(0.55f, 1.55f)
        // 1.2.15: openBoom must NOT freeze gain at 0 (sonif freeze left lastGain=0 → boomOut≈0
        // while openBoom=true in 1.2.14 fair log).
        lastGain = when {
            openBoom -> gain
            freeze -> lastGain * 0.99f
            else -> gain
        }

        var y = -drive * lastGain
        val pol = if (polarity >= 0f) 1f else -1f
        y *= pol
        // Dual 1-pole ~90 Hz → 40–120 (cabin 74/80) dominant, still kill mid hiss
        lp1 += lpCoeff * (y - lp1)
        lp2 += lpCoeff * (lp1 - lp2)
        // openBoom: nudge LPF state so first blocks aren't silent after long freeze
        if (openBoom && abs(lp2) < abs(y) * 0.35f) {
            lp1 = 0.55f * lp1 + 0.45f * y
            lp2 = 0.55f * lp2 + 0.45f * lp1
        }
        y = lp2.coerceIn(-1.15f, 1.15f)
        lastOutput = y
        accumulate(y)
        return y
    }

    private fun accumulate(y: Float) {
        val a = abs(y)
        sumSq += (y * y).toDouble()
        if (a > peakAbs) peakAbs = a
        blockN++
        if (blockN >= (sampleRate / 100).coerceAtLeast(64)) {
            lastRms = sqrt((sumSq / blockN).toFloat())
            lastPeak = peakAbs
            sumSq = 0.0
            peakAbs = 0f
            blockN = 0
        }
    }

    @Keep
    fun kpiLevel(): Float {
        val r = lastRms
        val p = lastPeak
        val l = abs(lastOutput)
        return when {
            r > 1e-6f -> r
            p > 1e-6f -> p * 0.5f
            else -> l
        }
    }

    @Keep
    fun reset() {
        ring.fill(0f)
        write = 0
        count = 0
        lp1 = 0f
        lp2 = 0f
        lastOutput = 0f
        lastGain = 0f
        lastRms = 0f
        lastPeak = 0f
        lastDelayUsed = 0
        lastUsedOpenFloor = false
        sumSq = 0.0
        peakAbs = 0f
        blockN = 0
    }
}
