package com.example.caranc.shared.latency

import androidx.annotation.Keep

/**
 * 依車速分箱預學習低頻係數，運行時插值，減少對即時 feedback 的依賴。
 */
@Keep
class PreLearnedAncBank(
    private val filterLength: Int,
    private val speedBinsKmh: FloatArray = floatArrayOf(0f, 30f, 60f, 90f, 120f)
) {
    private val snapshots = Array(speedBinsKmh.size) { FloatArray(filterLength) }
    private val filled = BooleanArray(speedBinsKmh.size)

    @Keep
    fun capture(speedKmh: Float, weights: FloatArray) {
        val index = nearestBin(speedKmh)
        val copyLen = filterLength.coerceAtMost(weights.size)
        for (i in 0 until copyLen) {
            snapshots[index][i] = weights[i]
        }
        filled[index] = true
    }

    @Keep
    fun blendedWeights(speedKmh: Float): FloatArray? {
        if (!filled.any { it }) return null
        val result = FloatArray(filterLength)
        var weightSum = 0f
        for (i in speedBinsKmh.indices) {
            if (!filled[i]) continue
            val distance = kotlin.math.abs(speedKmh - speedBinsKmh[i])
            val w = 1f / (1f + distance)
            weightSum += w
            for (j in result.indices) {
                result[j] += snapshots[i][j] * w
            }
        }
        if (weightSum <= 0f) return null
        for (j in result.indices) result[j] /= weightSum
        return result
    }

    @Keep
    fun applyBias(target: FloatArray, speedKmh: Float, blend: Float = 0.35f) {
        val learned = blendedWeights(speedKmh) ?: return
        val b = blend.coerceIn(0f, 0.8f)
        val len = filterLength.coerceAtMost(target.size).coerceAtMost(learned.size)
        for (i in 0 until len) {
            target[i] = target[i] * (1f - b) + learned[i] * b
        }
    }

    @Keep
    fun filledBinCount(): Int = filled.count { it }

    private fun nearestBin(speedKmh: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in speedBinsKmh.indices) {
            val d = kotlin.math.abs(speedKmh - speedBinsKmh[i])
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }
}