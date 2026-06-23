package com.example.caranc.shared

import androidx.annotation.Keep
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Keep
internal object FftUtils {

    fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < maxOf(2, value)) {
            result *= 2
        }
        return result
    }

    fun complexForward(data: FloatArray) {
        require(data.size % 2 == 0)
        val n = data.size / 2
        require(n > 0 && n and (n - 1) == 0)

        bitReversePermute(data, n)
        var length = 2
        while (length <= n) {
            val halfLength = length / 2
            val angle = -2.0 * PI / length
            val wStepReal = cos(angle).toFloat()
            val wStepImag = sin(angle).toFloat()

            var start = 0
            while (start < n) {
                var wReal = 1f
                var wImag = 0f
                for (offset in 0 until halfLength) {
                    val evenIndex = (start + offset) * 2
                    val oddIndex = (start + offset + halfLength) * 2

                    val evenReal = data[evenIndex]
                    val evenImag = data[evenIndex + 1]
                    val oddReal = data[oddIndex]
                    val oddImag = data[oddIndex + 1]

                    val twReal = wReal * oddReal - wImag * oddImag
                    val twImag = wReal * oddImag + wImag * oddReal

                    data[oddIndex] = evenReal - twReal
                    data[oddIndex + 1] = evenImag - twImag
                    data[evenIndex] = evenReal + twReal
                    data[evenIndex + 1] = evenImag + twImag

                    val nextWReal = wReal * wStepReal - wImag * wStepImag
                    wImag = wReal * wStepImag + wImag * wStepReal
                    wReal = nextWReal
                }
                start += length
            }
            length *= 2
        }
    }

    fun complexInverse(data: FloatArray, scale: Boolean) {
        conjugate(data)
        complexForward(data)
        conjugate(data)
        if (scale) {
            val n = data.size / 2f
            for (i in data.indices) {
                data[i] /= n
            }
        }
    }

    private fun conjugate(data: FloatArray) {
        for (i in 1 until data.size step 2) {
            data[i] = -data[i]
        }
    }

    private fun bitReversePermute(data: FloatArray, n: Int) {
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val iIndex = i * 2
                val jIndex = j * 2
                swapComplex(data, iIndex, jIndex)
            }
            var bit = n shr 1
            while (bit > 0 && j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
        }
    }

    private fun swapComplex(data: FloatArray, firstIndex: Int, secondIndex: Int) {
        val tempReal = data[firstIndex]
        val tempImag = data[firstIndex + 1]
        data[firstIndex] = data[secondIndex]
        data[firstIndex + 1] = data[secondIndex + 1]
        data[secondIndex] = tempReal
        data[secondIndex + 1] = tempImag
    }
}