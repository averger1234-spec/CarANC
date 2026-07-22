package com.example.caranc.shared.latency

import androidx.annotation.Keep
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * On-device road-condition **neural encoder** → latent vector for bank matching.
 *
 * Patent alignment (US20250069581A1 / EP4513360A1):
 * compress current reference conditions to a lower-dimensional latent, match historical
 * controller parameters. We use a tiny fixed MLP (no TFLite dependency) so every phone
 * can run it in the audio path.
 *
 * Architecture: features(8) → Dense(16)+ReLU → Dense(LATENT)+L2-normalize.
 * Weights are fixed, hand-structured (nonlinear mixes of speed/rough/energy/coherence/lat)
 * — not random noise — so nearby road states land in nearby latent neighborhoods.
 *
 * Online: each bank cell stores an EMA of latents at capture time; query latent is
 * matched by cosine similarity (soft-max blend).
 */
@Keep
class RoadConditionLatentEncoder(
    val latentDim: Int = LATENT_DIM
) {
    private val w1 = Array(HIDDEN) { FloatArray(FEAT_DIM) }
    private val b1 = FloatArray(HIDDEN)
    private val w2 = Array(LATENT_DIM) { FloatArray(HIDDEN) }
    private val b2 = FloatArray(LATENT_DIM)

    init {
        // Deterministic structured weights (seeded pattern) — nonlinear feature mixing.
        // Row patterns emphasize different physical axes so cosine match is meaningful.
        for (h in 0 until HIDDEN) {
            for (f in 0 until FEAT_DIM) {
                val phase = (h * 17 + f * 3) % 11
                val sign = if ((h + f) % 2 == 0) 1f else -1f
                w1[h][f] = sign * (0.15f + 0.08f * phase) * when (f) {
                    0 -> 1.2f   // speed
                    1 -> 1.1f   // roughness
                    2 -> 1.0f   // energy
                    3 -> 0.9f   // coherence
                    4 -> 0.7f   // latency class
                    5 -> 0.85f  // speed*rough
                    6 -> 0.8f   // energy*coherence
                    else -> 0.5f
                }
            }
            b1[h] = if (h % 3 == 0) 0.05f else -0.02f
        }
        for (l in 0 until LATENT_DIM) {
            for (h in 0 until HIDDEN) {
                val sign = if ((l * h) % 2 == 0) 1f else -1f
                w2[l][h] = sign * (0.12f + 0.04f * ((l + h) % 5))
            }
            b2[l] = 0f
        }
    }

    /**
     * Build normalized feature vector from live road / path state.
     * All inputs are physical quantities already used by CarANC.
     */
    @Keep
    fun features(
        speedKmh: Float,
        roughness: Float,
        energyProxy: Float,
        coherence: Float,
        latencyMs: Float
    ): FloatArray {
        val sp = (speedKmh / 120f).coerceIn(0f, 1.5f)
        val rg = (roughness / 2f).coerceIn(0f, 2f)
        val en = (energyProxy / 1.5f).coerceIn(0f, 1.5f)
        val co = coherence.coerceIn(0f, 1f)
        val lat = (latencyMs / 250f).coerceIn(0f, 2f)
        return floatArrayOf(
            sp,
            rg,
            en,
            co,
            lat,
            sp * rg,
            en * co,
            1f // bias feature
        )
    }

    /** Encode features → unit L2 latent. */
    @Keep
    fun encode(feat: FloatArray): FloatArray {
        val h = FloatArray(HIDDEN)
        for (i in 0 until HIDDEN) {
            var s = b1[i]
            val wi = w1[i]
            for (j in 0 until FEAT_DIM.coerceAtMost(feat.size)) {
                s += wi[j] * feat[j]
            }
            h[i] = if (s > 0f) s else 0f // ReLU
        }
        val z = FloatArray(LATENT_DIM)
        var norm2 = 0f
        for (i in 0 until LATENT_DIM) {
            var s = b2[i]
            val wi = w2[i]
            for (j in 0 until HIDDEN) s += wi[j] * h[j]
            z[i] = s
            norm2 += s * s
        }
        val inv = 1f / sqrt(norm2.coerceAtLeast(1e-8f))
        for (i in 0 until LATENT_DIM) z[i] *= inv
        return z
    }

    @Keep
    fun encodeRoad(
        speedKmh: Float,
        roughness: Float,
        energyProxy: Float,
        coherence: Float,
        latencyMs: Float
    ): FloatArray = encode(features(speedKmh, roughness, energyProxy, coherence, latencyMs))

    /** Cosine similarity for unit vectors = dot product. */
    @Keep
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = a.size.coerceAtMost(b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s.coerceIn(-1f, 1f)
    }

    companion object {
        const val FEAT_DIM = 8
        const val HIDDEN = 16
        const val LATENT_DIM = 8
    }
}

/**
 * Soft-max weights from cosine similarities to bank cell latents.
 */
@Keep
object LatentSoftmax {
    fun weights(cosines: FloatArray, temperature: Float = 0.25f): FloatArray {
        val t = temperature.coerceIn(0.05f, 1f)
        var maxC = Float.NEGATIVE_INFINITY
        for (c in cosines) if (c > maxC) maxC = c
        val w = FloatArray(cosines.size)
        var sum = 0f
        for (i in cosines.indices) {
            val e = exp(((cosines[i] - maxC) / t).toDouble()).toFloat()
            w[i] = e
            sum += e
        }
        if (sum < 1e-12f) {
            val u = 1f / w.size.coerceAtLeast(1)
            for (i in w.indices) w[i] = u
            return w
        }
        for (i in w.indices) w[i] /= sum
        return w
    }
}
