package com.example.caranc.shared.latency

import androidx.annotation.Keep
import kotlin.math.abs
import kotlin.math.exp

/**
 * Fixed-filter / pre-trained low-band weight bank.
 *
 * #7: 2D lookup **speed × roughness** as primary under high latency;
 * adaptive LMS is secondary (blend / residual only).
 *
 * B-fix: always has a **default rumble FIR prior** so FF_PREVIEW never gets fixedBankOut=0
 * when bins are empty; capture is EMA into nearest cell; finishLearning seeds neighbors.
 */
@Keep
class PreLearnedAncBank(
    private val filterLength: Int,
    private val speedBinsKmh: FloatArray = floatArrayOf(0f, 30f, 50f, 70f, 90f, 120f),
    private val roughnessBins: FloatArray = floatArrayOf(0.25f, 0.6f, 1.0f, 1.4f, 2.0f)
) {
    private val nSpeed = speedBinsKmh.size
    private val nRough = roughnessBins.size
    private val cellCount = nSpeed * nRough

    private val snapshots = Array(cellCount) { FloatArray(filterLength) }
    private val filled = BooleanArray(cellCount)
    private val hitCount = IntArray(cellCount)

    /** Soft low-rumble prior (decaying taps) — used when no cell learned yet. */
    private val defaultPrior = FloatArray(filterLength) { i ->
        // Mild causal FIR ~ low-frequency emphasis (not identity white-noise)
        val t = i.toFloat()
        val env = exp(-t / (filterLength * 0.22f).coerceAtLeast(4f))
        (0.12f * env * if (i % 2 == 0) 1f else -0.35f).coerceIn(-0.25f, 0.25f)
    }

    private var defaultsSeeded = false

    private fun cellIndex(speedIdx: Int, roughIdx: Int): Int = speedIdx * nRough + roughIdx

    init {
        seedDefaultPriorIntoAllCells(force = false)
    }

    /**
     * Fill empty cells with default prior so fixedFilterSample always has weights.
     * Does not overwrite cells that already have real captures (hitCount>0) unless [force].
     */
    @Keep
    fun seedDefaultPriorIntoAllCells(force: Boolean = false) {
        for (idx in 0 until cellCount) {
            if (!force && hitCount[idx] > 0) continue
            for (i in 0 until filterLength) {
                snapshots[idx][i] = defaultPrior[i]
            }
            filled[idx] = true
            if (hitCount[idx] == 0) hitCount[idx] = 0 // keep 0 = still "prior only"
        }
        defaultsSeeded = true
    }

    /** After learning: write weights into current cell + soft-seed neighbors. */
    @Keep
    fun seedFromLearning(speedKmh: Float, weights: FloatArray, roughness: Float = 0.5f) {
        capture(speedKmh, weights, roughness, forceAlpha = 1f)
        val si = nearestSpeedBin(speedKmh)
        val ri = nearestRoughBin(roughness)
        // Soft-seed adjacent speed bins so 50–90 km/h band is not empty
        for (ds in -1..1) {
            for (dr in -1..1) {
                if (ds == 0 && dr == 0) continue
                val s2 = (si + ds).coerceIn(0, nSpeed - 1)
                val r2 = (ri + dr).coerceIn(0, nRough - 1)
                val idx = cellIndex(s2, r2)
                if (hitCount[idx] > 2) continue
                val alpha = 0.35f
                val copyLen = filterLength.coerceAtMost(weights.size)
                for (i in 0 until copyLen) {
                    snapshots[idx][i] = snapshots[idx][i] * (1f - alpha) + weights[i] * alpha
                }
                filled[idx] = true
                if (hitCount[idx] == 0) hitCount[idx] = 1
            }
        }
    }

    @Keep
    fun capture(speedKmh: Float, weights: FloatArray, roughness: Float = 0.5f, forceAlpha: Float? = null) {
        val si = nearestSpeedBin(speedKmh)
        val ri = nearestRoughBin(roughness)
        val index = cellIndex(si, ri)
        val copyLen = filterLength.coerceAtMost(weights.size)
        val n = hitCount[index]
        val alpha = forceAlpha ?: if (n == 0) 1f else (1f / (1f + n.coerceAtMost(6)))
        for (i in 0 until copyLen) {
            snapshots[index][i] = snapshots[index][i] * (1f - alpha) + weights[i] * alpha
        }
        filled[index] = true
        hitCount[index] = (n + 1).coerceAtMost(1000)
    }

    @Keep
    fun capture(speedKmh: Float, weights: FloatArray) = capture(speedKmh, weights, roughness = 0.5f)

    @Keep
    fun blendedWeights(speedKmh: Float, roughness: Float = 0.5f): FloatArray? {
        if (!defaultsSeeded) seedDefaultPriorIntoAllCells()
        if (!filled.any { it }) {
            return defaultPrior.copyOf()
        }
        val result = FloatArray(filterLength)
        var weightSum = 0f
        for (si in 0 until nSpeed) {
            for (ri in 0 until nRough) {
                val idx = cellIndex(si, ri)
                if (!filled[idx]) continue
                val dSpeed = abs(speedKmh - speedBinsKmh[si]) / 25f
                val dRough = abs(roughness - roughnessBins[ri]) / 0.5f
                val dist = dSpeed + dRough
                // Prefer cells with real captures slightly
                val learnedBoost = if (hitCount[idx] > 0) 1.35f else 0.85f
                val w = learnedBoost / (1f + dist)
                weightSum += w
                val snap = snapshots[idx]
                for (j in result.indices) {
                    result[j] += snap[j] * w
                }
            }
        }
        if (weightSum <= 0f) return defaultPrior.copyOf()
        for (j in result.indices) result[j] /= weightSum
        return result
    }

    @Keep
    fun blendedWeights(speedKmh: Float): FloatArray? = blendedWeights(speedKmh, roughness = 0.5f)

    @Keep
    fun applyBias(target: FloatArray, speedKmh: Float, blend: Float = 0.35f, roughness: Float = 0.5f) {
        val learned = blendedWeights(speedKmh, roughness) ?: return
        val b = blend.coerceIn(0f, 0.95f)
        val len = filterLength.coerceAtMost(target.size).coerceAtMost(learned.size)
        for (i in 0 until len) {
            target[i] = target[i] * (1f - b) + learned[i] * b
        }
    }

    /**
     * Direct fixed FIR sample (primary under FF_PREVIEW_ONLY).
     * Always returns a value (default prior if needed) — never silent under B-fix.
     */
    @Keep
    fun fixedFilterSample(
        speedKmh: Float,
        roughness: Float,
        xRing: FloatArray,
        xWriteIndex: Int
    ): Float {
        val w = blendedWeights(speedKmh, roughness) ?: defaultPrior
        val n = filterLength.coerceAtMost(w.size).coerceAtMost(xRing.size)
        if (n <= 0) return 0f
        var y = 0f
        var idx = (xWriteIndex - 1 + xRing.size) % xRing.size
        for (k in 0 until n) {
            y += w[k] * xRing[idx]
            idx = if (idx == 0) xRing.size - 1 else idx - 1
        }
        // Scale with speed so idle stays quiet, drive gets more FF
        val speedScale = ((speedKmh - 15f) / 55f).coerceIn(0.15f, 1.35f)
        val roughScale = (0.55f + roughness * 0.35f).coerceIn(0.5f, 1.6f)
        return (-y * speedScale * roughScale).coerceIn(-0.65f, 0.65f)
    }

    /** Cells with at least one real capture (not only default prior). */
    @Keep
    fun learnedBinCount(): Int = hitCount.count { it > 0 }

    @Keep
    fun filledBinCount(): Int = filled.count { it }

    fun speedBinCount(): Int = nSpeed
    fun roughnessBinCount(): Int = nRough
    fun totalCellCount(): Int = cellCount

    private fun nearestSpeedBin(speedKmh: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in speedBinsKmh.indices) {
            val d = abs(speedKmh - speedBinsKmh[i])
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun nearestRoughBin(roughness: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in roughnessBins.indices) {
            val d = abs(roughness - roughnessBins[i])
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }
}
