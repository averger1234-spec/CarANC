package com.example.caranc.shared.latency

import androidx.annotation.Keep
import kotlin.math.abs

/**
 * Fixed-filter / pre-trained low-band weight bank.
 *
 * #7: 2D lookup **speed × roughness** as primary under high latency;
 * adaptive LMS is secondary (blend / residual only).
 *
 * Bins are coarse on purpose (phone IMU roughness + GPS speed are noisy).
 * Runtime: bilinear-style distance-weighted blend across filled cells.
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

    private fun cellIndex(speedIdx: Int, roughIdx: Int): Int = speedIdx * nRough + roughIdx

    @Keep
    fun capture(speedKmh: Float, weights: FloatArray, roughness: Float = 0.5f) {
        val si = nearestSpeedBin(speedKmh)
        val ri = nearestRoughBin(roughness)
        val index = cellIndex(si, ri)
        val copyLen = filterLength.coerceAtMost(weights.size)
        // EMA into bin so one pothole doesn't overwrite a good cell
        val n = hitCount[index]
        val alpha = if (n == 0) 1f else (1f / (1f + n.coerceAtMost(8)))
        for (i in 0 until copyLen) {
            snapshots[index][i] = snapshots[index][i] * (1f - alpha) + weights[i] * alpha
        }
        filled[index] = true
        hitCount[index] = (n + 1).coerceAtMost(1000)
    }

    /** Backward-compatible capture without roughness. */
    @Keep
    fun capture(speedKmh: Float, weights: FloatArray) = capture(speedKmh, weights, roughness = 0.5f)

    @Keep
    fun blendedWeights(speedKmh: Float, roughness: Float = 0.5f): FloatArray? {
        if (!filled.any { it }) return null
        val result = FloatArray(filterLength)
        var weightSum = 0f
        for (si in 0 until nSpeed) {
            for (ri in 0 until nRough) {
                val idx = cellIndex(si, ri)
                if (!filled[idx]) continue
                val dSpeed = abs(speedKmh - speedBinsKmh[si]) / 25f
                val dRough = abs(roughness - roughnessBins[ri]) / 0.5f
                val dist = dSpeed + dRough
                val w = 1f / (1f + dist)
                weightSum += w
                val snap = snapshots[idx]
                for (j in result.indices) {
                    result[j] += snap[j] * w
                }
            }
        }
        if (weightSum <= 0f) return null
        for (j in result.indices) result[j] /= weightSum
        return result
    }

    /** Speed-only blend (legacy callers). */
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
     * [xHistory] newest-first or ring: uses last [filterLength] samples ending at [xNewestIndex].
     */
    @Keep
    fun fixedFilterSample(
        speedKmh: Float,
        roughness: Float,
        xRing: FloatArray,
        xWriteIndex: Int
    ): Float {
        val w = blendedWeights(speedKmh, roughness) ?: return 0f
        val n = filterLength.coerceAtMost(w.size).coerceAtMost(xRing.size)
        var y = 0f
        var idx = (xWriteIndex - 1 + xRing.size) % xRing.size
        for (k in 0 until n) {
            y += w[k] * xRing[idx]
            idx = if (idx == 0) xRing.size - 1 else idx - 1
        }
        return (-y).coerceIn(-0.7f, 0.7f)
    }

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
