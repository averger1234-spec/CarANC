package com.example.caranc.shared.latency

import androidx.annotation.Keep
import kotlin.math.abs
import kotlin.math.exp

/**
 * Fixed-filter / pre-trained low-band weight bank with **neural latent** matching.
 *
 * #7 + patent US20250069581A1 / EP4513360A1:
 * road condition → encoder latent → soft-max match historical controller FIR weights.
 *
 * Fallback: if encoder path disabled, L1 on speed×rough×energy (legacy 3-D).
 *
 * B-fix: default rumble FIR prior so fixedBankOut never stays 0 when bins empty.
 */
@Keep
class PreLearnedAncBank(
    private val filterLength: Int,
    private val speedBinsKmh: FloatArray = floatArrayOf(0f, 30f, 50f, 70f, 90f, 120f),
    private val roughnessBins: FloatArray = floatArrayOf(0.25f, 0.6f, 1.0f, 1.4f, 2.0f),
    private val useNeuralLatent: Boolean = true
) {
    private val nSpeed = speedBinsKmh.size
    private val nRough = roughnessBins.size
    private val cellCount = nSpeed * nRough

    private val snapshots = Array(cellCount) { FloatArray(filterLength) }
    private val filled = BooleanArray(cellCount)
    private val hitCount = IntArray(cellCount)
    /** Per-cell energy proxy EMA (legacy + logging). */
    private val cellEnergy = FloatArray(cellCount) { 0.5f }
    /** Per-cell latent EMA from [RoadConditionLatentEncoder]. */
    private val cellLatent = Array(cellCount) { FloatArray(RoadConditionLatentEncoder.LATENT_DIM) }
    private val cellLatentValid = BooleanArray(cellCount)

    private val encoder = RoadConditionLatentEncoder()

    /** Last match quality 0..1 (peak soft-max mass). */
    var lastMatchQuality: Float = 0.5f
        private set
    /** Last best cosine similarity (−1..1). */
    var lastMatchCosine: Float = 0f
        private set
    /** Last query latent (copy for logs). */
    var lastQueryLatent: FloatArray = FloatArray(RoadConditionLatentEncoder.LATENT_DIM)
        private set
    var lastLatencyMsUsed: Float = 150f
        private set
    var lastCoherenceUsed: Float = 0.5f
        private set
    val neuralLatentEnabled: Boolean get() = useNeuralLatent
    val latentDim: Int get() = RoadConditionLatentEncoder.LATENT_DIM

    private val defaultPrior = FloatArray(filterLength) { i ->
        val t = i.toFloat()
        val env = exp(-t / (filterLength * 0.22f).coerceAtLeast(4f))
        (0.12f * env * if (i % 2 == 0) 1f else -0.35f).coerceIn(-0.25f, 0.25f)
    }

    private var defaultsSeeded = false

    private fun cellIndex(speedIdx: Int, roughIdx: Int): Int = speedIdx * nRough + roughIdx

    init {
        seedDefaultPriorIntoAllCells(force = false)
        // Seed cell latents at bin centers so empty cells still match sensibly
        for (si in 0 until nSpeed) {
            for (ri in 0 until nRough) {
                val idx = cellIndex(si, ri)
                val z = encoder.encodeRoad(
                    speedKmh = speedBinsKmh[si],
                    roughness = roughnessBins[ri],
                    energyProxy = 0.5f,
                    coherence = 0.55f,
                    latencyMs = 150f
                )
                for (k in z.indices) cellLatent[idx][k] = z[k]
                cellLatentValid[idx] = true
            }
        }
    }

    @Keep
    fun seedDefaultPriorIntoAllCells(force: Boolean = false) {
        for (idx in 0 until cellCount) {
            if (!force && hitCount[idx] > 0) continue
            for (i in 0 until filterLength) {
                snapshots[idx][i] = defaultPrior[i]
            }
            filled[idx] = true
            if (hitCount[idx] == 0) hitCount[idx] = 0
        }
        defaultsSeeded = true
    }

    @Keep
    fun seedFromLearning(speedKmh: Float, weights: FloatArray, roughness: Float = 0.5f) {
        capture(speedKmh, weights, roughness, forceAlpha = 1f)
        val si = nearestSpeedBin(speedKmh)
        val ri = nearestRoughBin(roughness)
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
    fun capture(
        speedKmh: Float,
        weights: FloatArray,
        roughness: Float = 0.5f,
        forceAlpha: Float? = null,
        energyProxy: Float = 0.5f,
        coherence: Float = 0.55f,
        latencyMs: Float = 150f
    ) {
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
        val e = energyProxy.coerceIn(0f, 1.5f)
        cellEnergy[index] = cellEnergy[index] * (1f - alpha) + e * alpha

        // Update cell latent EMA (patent: store compressed road condition with params)
        val z = encoder.encodeRoad(speedKmh, roughness, e, coherence, latencyMs)
        val lat = cellLatent[index]
        if (!cellLatentValid[index]) {
            for (k in z.indices) lat[k] = z[k]
            cellLatentValid[index] = true
        } else {
            for (k in z.indices) lat[k] = lat[k] * (1f - alpha) + z[k] * alpha
            // re-normalize
            var n2 = 0f
            for (k in lat.indices) n2 += lat[k] * lat[k]
            val inv = 1f / kotlin.math.sqrt(n2.coerceAtLeast(1e-8f))
            for (k in lat.indices) lat[k] *= inv
        }
    }

    @Keep
    fun capture(speedKmh: Float, weights: FloatArray) = capture(speedKmh, weights, roughness = 0.5f)

    /**
     * Neural latent soft-max blend (preferred) or legacy 3-D L1.
     */
    @Keep
    fun blendedWeights(
        speedKmh: Float,
        roughness: Float = 0.5f,
        energyProxy: Float = 0.5f,
        coherence: Float = 0.55f,
        latencyMs: Float = 150f
    ): FloatArray? {
        if (!defaultsSeeded) seedDefaultPriorIntoAllCells()
        lastLatencyMsUsed = latencyMs
        lastCoherenceUsed = coherence
        if (!filled.any { it }) {
            lastMatchQuality = 0.2f
            lastMatchCosine = 0f
            return defaultPrior.copyOf()
        }

        if (useNeuralLatent) {
            return blendNeural(speedKmh, roughness, energyProxy, coherence, latencyMs)
        }
        return blendLegacy3d(speedKmh, roughness, energyProxy)
    }

    private fun blendNeural(
        speedKmh: Float,
        roughness: Float,
        energyProxy: Float,
        coherence: Float,
        latencyMs: Float
    ): FloatArray {
        val q = encoder.encodeRoad(speedKmh, roughness, energyProxy, coherence, latencyMs)
        for (i in q.indices) lastQueryLatent[i] = q[i]

        val cos = FloatArray(cellCount)
        var bestCos = -1f
        for (idx in 0 until cellCount) {
            if (!filled[idx] || !cellLatentValid[idx]) {
                cos[idx] = -1f
                continue
            }
            val c = encoder.cosine(q, cellLatent[idx])
            // Prefer learned cells slightly
            val cAdj = if (hitCount[idx] > 0) c + 0.04f else c - 0.02f
            cos[idx] = cAdj.coerceIn(-1f, 1f)
            if (cAdj > bestCos) bestCos = cAdj
        }
        lastMatchCosine = bestCos.coerceIn(-1f, 1f)

        val sm = LatentSoftmax.weights(cos, temperature = 0.22f)
        val result = FloatArray(filterLength)
        var peak = 0f
        for (idx in 0 until cellCount) {
            val w = sm[idx]
            if (w > peak) peak = w
            if (w < 1e-6f) continue
            val snap = snapshots[idx]
            val learnedBoost = if (hitCount[idx] > 0) 1.12f else 0.92f
            val ww = w * learnedBoost
            for (j in result.indices) {
                result[j] += snap[j] * ww
            }
        }
        var sumW = 0f
        for (idx in 0 until cellCount) {
            sumW += sm[idx] * (if (hitCount[idx] > 0) 1.12f else 0.92f)
        }
        if (sumW > 1e-8f) {
            for (j in result.indices) result[j] /= sumW
        }
        lastMatchQuality = peak.coerceIn(0.1f, 1f)
        return result
    }

    private fun blendLegacy3d(speedKmh: Float, roughness: Float, energyProxy: Float): FloatArray {
        val eQuery = energyProxy.coerceIn(0f, 1.5f)
        val result = FloatArray(filterLength)
        var weightSum = 0f
        var bestW = 0f
        for (si in 0 until nSpeed) {
            for (ri in 0 until nRough) {
                val idx = cellIndex(si, ri)
                if (!filled[idx]) continue
                val dSpeed = abs(speedKmh - speedBinsKmh[si]) / 25f
                val dRough = abs(roughness - roughnessBins[ri]) / 0.5f
                val dEnergy = abs(eQuery - cellEnergy[idx]) / 0.6f
                val dist = dSpeed + dRough + 0.65f * dEnergy
                val learnedBoost = if (hitCount[idx] > 0) 1.55f else 0.75f
                val w = learnedBoost / (1f + dist * dist)
                if (w > bestW) bestW = w
                weightSum += w
                val snap = snapshots[idx]
                for (j in result.indices) result[j] += snap[j] * w
            }
        }
        if (weightSum <= 0f) {
            lastMatchQuality = 0.2f
            return defaultPrior.copyOf()
        }
        for (j in result.indices) result[j] /= weightSum
        lastMatchQuality = (bestW / weightSum * cellCount * 0.15f).coerceIn(0.15f, 1f)
        lastMatchCosine = 0f
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

    @Keep
    fun fixedFilterSample(
        speedKmh: Float,
        roughness: Float,
        xRing: FloatArray,
        xWriteIndex: Int,
        energyProxy: Float = 0.5f,
        coherence: Float = 0.55f,
        latencyMs: Float = 150f
    ): Float {
        val w = blendedWeights(speedKmh, roughness, energyProxy, coherence, latencyMs) ?: defaultPrior
        val n = filterLength.coerceAtMost(w.size).coerceAtMost(xRing.size)
        if (n <= 0) return 0f
        var y = 0f
        var idx = (xWriteIndex - 1 + xRing.size) % xRing.size
        for (k in 0 until n) {
            y += w[k] * xRing[idx]
            idx = if (idx == 0) xRing.size - 1 else idx - 1
        }
        val speedScale = ((speedKmh - 15f) / 55f).coerceIn(0.15f, 1.35f)
        val roughScale = (0.55f + roughness * 0.35f).coerceIn(0.5f, 1.6f)
        val conf = (0.55f + 0.45f * lastMatchQuality).coerceIn(0.5f, 1.1f)
        return (-y * speedScale * roughScale * conf).coerceIn(-0.65f, 0.65f)
    }

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
