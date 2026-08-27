package com.example.caranc.shared.latency

import com.example.caranc.shared.FftUtils
import com.example.caranc.shared.Keep
import kotlin.math.PI
import kotlin.math.cos

/**
 * Lock notch F0 to **cabin mic peaks**, not speed-formula ranges.
 * 1.2.39: boom 35–110, tire 150–400, wind 500–2400.
 */
@Keep
class CabinPeakTracker(
    private val sampleRate: Int,
    private val window: Int = 8192
) {
    private val ring = FloatArray(window)
    private var write = 0
    private var filled = 0

    var boomHz: Float = 0f
        private set
    var tireHz: Float = 0f
        private set
    var windHz: Float = 0f
        private set

    @Keep
    fun push(x: Float) {
        ring[write] = x.coerceIn(-1.5f, 1.5f)
        write = (write + 1) % window
        if (filled < window) filled++
        if (filled >= window && write == 0) {
            analyze()
        }
    }

    @Keep
    fun reset() {
        ring.fill(0f)
        write = 0
        filled = 0
        boomHz = 0f
        tireHz = 0f
        windHz = 0f
    }

    private fun analyze() {
        val n = window
        val fft = FloatArray(n * 2)
        val last = (n - 1).coerceAtLeast(1)
        for (i in 0 until n) {
            val idx = (write + i) % n
            val w = (0.5f * (1f - cos(2.0 * PI * i / last))).toFloat()
            fft[i * 2] = ring[idx] * w
        }
        FftUtils.complexForward(fft)
        val binHz = sampleRate.toFloat() / n
        val boom = peakIn(fft, n, binHz, 35f, 110f)
        val tire = peakIn(fft, n, binHz, 150f, 400f)
        val wind = peakIn(fft, n, binHz, 500f, 2400f)
        boomHz = ema(boomHz, boom.hz, 0.18f)
        // A 50 Hz boom's FFT skirt always "wins" the 150 Hz edge. Require tire/wind
        // to be a real peak, not 20+ dB down from boom.
        val tireOk = tire.hz > 1f && (boom.mag < 1e-12f || tire.mag > boom.mag * 0.05f)
        val windOk = wind.hz > 1f && (boom.mag < 1e-12f || wind.mag > boom.mag * 0.03f)
        tireHz = ema(tireHz, if (tireOk) tire.hz else 0f, 0.18f)
        windHz = ema(windHz, if (windOk) wind.hz else 0f, 0.18f)
    }

    companion object {
        private data class Peak(val hz: Float, val mag: Float)

        private fun peakIn(fft: FloatArray, n: Int, binHz: Float, fLo: Float, fHi: Float): Peak {
            val i0 = (fLo / binHz).toInt().coerceIn(1, n / 2 - 3)
            val i1 = (fHi / binHz).toInt().coerceIn(i0 + 2, n / 2 - 1)
            var bestI = i0
            var best = 0f
            var sum = 0.0
            for (i in i0..i1) {
                val re = fft[i * 2]
                val im = fft[i * 2 + 1]
                val m = re * re + im * im
                sum += m
                if (m > best) {
                    best = m
                    bestI = i
                }
            }
            val mean = (sum / (i1 - i0 + 1)).toFloat()
            if (bestI <= i0 + 1 || bestI >= i1 - 1) return Peak(0f, 0f)
            if (best < mean * 6f || best < 1e-8f) return Peak(0f, 0f)
            return Peak(bestI * binHz, best)
        }

        private fun ema(prev: Float, next: Float, a: Float): Float {
            if (next <= 1f) return prev * 0.97f
            return if (prev <= 1f) next else prev * (1f - a) + next * a
        }
    }
}
