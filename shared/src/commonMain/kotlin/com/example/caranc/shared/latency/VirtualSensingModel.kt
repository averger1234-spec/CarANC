package com.example.caranc.shared.latency

import androidx.annotation.Keep

/**
 * 將麥克風誤差轉換為駕駛耳點虛擬誤差，供 FXLMS 權重更新使用。
 */
@Keep
class VirtualSensingModel(
    cabinPath: FloatArray,
    private val pathLength: Int = 32
) {
    private val path = FloatArray(pathLength) { 0f }
    private val history = FloatArray(128) { 0f }
    private var historyIndex = 0

    init {
        for (i in 0 until pathLength.coerceAtMost(cabinPath.size)) {
            path[i] = cabinPath[i]
        }
    }

    var lastVirtualError = 0f
        private set
    var lastRoomComponent = 0f
        private set

    @Keep
    fun update(micSample: Float): Float {
        history[historyIndex] = micSample
        var room = 0f
        for (j in path.indices) {
            val idx = (historyIndex - j) and 127
            room += path[j] * history[idx]
        }
        lastRoomComponent = room
        lastVirtualError = (micSample - 0.35f * room).coerceIn(-1.5f, 1.5f)
        historyIndex = (historyIndex + 1) and 127
        return lastVirtualError
    }

    @Keep
    fun bindPath(cabinPath: FloatArray) {
        path.fill(0f)
        for (i in 0 until pathLength.coerceAtMost(cabinPath.size)) {
            path[i] = cabinPath[i]
        }
    }
}