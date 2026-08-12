package com.example.caranc.shared.model

import com.example.caranc.shared.Keep
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * **無車速備用**：GPS 掉線保持 + IMU 振動代車速（供 [SpeedScheduledNvhGains] 與導引 valid）。
 *
 * 優先序：
 * 1. **gps** — 有效 GPS 速度
 * 2. **gps_hold** — 最近有效 GPS 在 [HOLD_MAX_SEC] 內，緩慢衰減
 * 3. **imu_proxy** — 線加速度量級映射粗速（非精確，標 source 以便 log）
 * 4. **none** — 靜止／無感測
 *
 * [validForDsp]：可餵 MultiBand / SpeedScheduled
 * [validForRoadTest]：gps 或短 hold（嚴格路測 KPI）；imu_proxy 不算「真 GPS 路測」但可累導引 valid（較寬）
 */
@Keep
data class FusedVehicleSpeed(
    val speedKmh: Float,
    /** 餵 DSP / SpeedScheduled */
    val validForDsp: Boolean,
    /** 較嚴：僅 gps / 新鮮 hold */
    val validForRoadTest: Boolean,
    /** gps | gps_hold | imu_proxy | none */
    val source: String,
    val rawGpsValid: Boolean,
    val rawGpsKmh: Float,
    val holdAgeSec: Float,
    val imuProxyKmh: Float,
    val accelMag: Float
)

@Keep
data class VehicleSpeedFusionState(
    val lastGoodGpsKmh: Float = 0f,
    val lastGoodGpsAtMs: Long = 0L,
    val smoothedOutKmh: Float = 0f,
    val accelEma: Float = 0f,
    val motionEma: Float = 0f
)

@Keep
object VehicleSpeedFusion {

    /** GPS 掉線後仍沿用上一檔速度的最長秒數 */
    const val HOLD_MAX_SEC = 25f
    /** hold 每秒衰減比例（緩慢降，避免隧道瞬間掉到 idle 表） */
    const val HOLD_DECAY_PER_SEC = 0.012f
    /** 低於此視為幾乎靜止，不 hold */
    const val HOLD_MIN_KMH = 8f
    /** IMU 代速上限（避免亂甩到高速表） */
    const val IMU_PROXY_MAX_KMH = 75f

    fun fuse(
        gpsSpeedKmh: Float,
        gpsValid: Boolean,
        accelMag: Float,
        nowMs: Long,
        state: VehicleSpeedFusionState
    ): Pair<FusedVehicleSpeed, VehicleSpeedFusionState> {
        val accel = max(0f, accelMag)
        val accelEma = if (state.accelEma <= 0f) accel else state.accelEma * 0.88f + accel * 0.12f
        // 持續振動（行駛）vs 短暫拿手機
        val motion = if (accelEma > 0.12f) 1f else 0f
        val motionEma = state.motionEma * 0.92f + motion * 0.08f

        val imuProxy = imuProxyKmh(accelEma, motionEma)

        if (gpsValid && gpsSpeedKmh >= 0f) {
            val sm = if (state.smoothedOutKmh <= 0f) {
                gpsSpeedKmh
            } else {
                state.smoothedOutKmh * 0.75f + gpsSpeedKmh * 0.25f
            }
            val next = state.copy(
                lastGoodGpsKmh = sm,
                lastGoodGpsAtMs = nowMs,
                smoothedOutKmh = sm,
                accelEma = accelEma,
                motionEma = motionEma
            )
            val out = FusedVehicleSpeed(
                speedKmh = sm,
                validForDsp = true,
                validForRoadTest = true,
                source = "gps",
                rawGpsValid = true,
                rawGpsKmh = gpsSpeedKmh,
                holdAgeSec = 0f,
                imuProxyKmh = imuProxy,
                accelMag = accelEma
            )
            return out to next
        }

        // --- GPS 無效：hold ---
        val ageSec = if (state.lastGoodGpsAtMs > 0L) {
            (nowMs - state.lastGoodGpsAtMs).coerceAtLeast(0L) / 1000f
        } else {
            Float.POSITIVE_INFINITY
        }
        if (state.lastGoodGpsKmh >= HOLD_MIN_KMH && ageSec <= HOLD_MAX_SEC) {
            val decay = (1f - HOLD_DECAY_PER_SEC * ageSec).coerceIn(0.55f, 1f)
            // 振動仍在 → 少衰減；幾乎靜止 → 多衰減
            val vibBoost = if (motionEma > 0.35f) 1f else 0.85f
            var hold = state.lastGoodGpsKmh * decay * vibBoost
            // IMU 代速若明顯更高，略上修 hold（隧道內仍在開）
            if (imuProxy > hold + 8f && motionEma > 0.4f) {
                hold = hold * 0.7f + imuProxy * 0.3f
            }
            hold = hold.coerceIn(0f, 130f)
            val next = state.copy(
                smoothedOutKmh = hold,
                accelEma = accelEma,
                motionEma = motionEma
            )
            val out = FusedVehicleSpeed(
                speedKmh = hold,
                validForDsp = true,
                validForRoadTest = ageSec <= 12f, // 前 12s hold 仍算較嚴路測
                source = "gps_hold",
                rawGpsValid = false,
                rawGpsKmh = gpsSpeedKmh.coerceAtLeast(0f),
                holdAgeSec = ageSec,
                imuProxyKmh = imuProxy,
                accelMag = accelEma
            )
            return out to next
        }

        // --- IMU 代車速 ---
        if (imuProxy >= 12f && motionEma > 0.28f) {
            val sm = if (state.smoothedOutKmh > 5f) {
                state.smoothedOutKmh * 0.85f + imuProxy * 0.15f
            } else {
                imuProxy
            }
            val next = state.copy(
                smoothedOutKmh = sm,
                accelEma = accelEma,
                motionEma = motionEma
                // 不更新 lastGoodGps*
            )
            val out = FusedVehicleSpeed(
                speedKmh = sm,
                validForDsp = true,
                validForRoadTest = false,
                source = "imu_proxy",
                rawGpsValid = false,
                rawGpsKmh = 0f,
                holdAgeSec = if (ageSec.isFinite()) ageSec else -1f,
                imuProxyKmh = imuProxy,
                accelMag = accelEma
            )
            return out to next
        }

        // none
        val next = state.copy(
            smoothedOutKmh = state.smoothedOutKmh * 0.9f,
            accelEma = accelEma,
            motionEma = motionEma
        )
        val out = FusedVehicleSpeed(
            speedKmh = 0f,
            validForDsp = false,
            validForRoadTest = false,
            source = "none",
            rawGpsValid = false,
            rawGpsKmh = 0f,
            holdAgeSec = if (ageSec.isFinite()) ageSec else -1f,
            imuProxyKmh = imuProxy,
            accelMag = accelEma
        )
        return out to next
    }

    /**
     * 粗映射：線加速度量級 → 估計 km/h（經驗曲線，非物理積分）。
     * 僅在持續 motion 時有意義；短促晃動靠 motionEma 壓制。
     */
    fun imuProxyKmh(accelEma: Float, motionEma: Float): Float {
        if (motionEma < 0.15f || accelEma < 0.08f) return 0f
        // 分段：idle / 市區低速 / 市區 / 快速 / 高速振動
        val base = when {
            accelEma < 0.15f -> 5f + accelEma * 40f
            accelEma < 0.35f -> 18f + (accelEma - 0.15f) * 90f
            accelEma < 0.70f -> 36f + (accelEma - 0.35f) * 55f
            accelEma < 1.20f -> 55f + (accelEma - 0.70f) * 30f
            else -> 70f + min(10f, (accelEma - 1.20f) * 8f)
        }
        val scaled = base * (0.55f + 0.45f * motionEma.coerceIn(0f, 1f))
        return scaled.coerceIn(0f, IMU_PROXY_MAX_KMH)
    }
}
