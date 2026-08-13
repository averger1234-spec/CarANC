package com.example.caranc.shared.model

import com.example.caranc.shared.Keep
import kotlin.math.floor
import kotlin.math.min

/**
 * **Speed-scheduled anti gains** for product NVH (road / tire / wind).
 *
 * Feedforward gain schedule vs vehicle speed (every [BIN_KMH] km/h), less trust mic alone.
 * Units: relative scales on band gains / final anti (not absolute dB).
 *
 * **Wind (WIND_SHEAR)**: product requirement is **active suppression** (push mid/high + totalAnti),
 * not "protect only / do-not-chase". Artifacts are managed in the processor (leakage/clip/freeze),
 * not by zeroing wind effort.
 */
@Keep
data class SpeedNvhGainSchedule(
    /** Floor speed bin label, e.g. 45 means [45, 50) km/h. */
    val speedBinKmh: Int,
    val lowGain: Float,
    val midGain: Float,
    /** High-band scale; wind uses non-zero to chase aero hiss. */
    val highGain: Float,
    /** Multiplies final anti sample (after band mix). */
    val totalAntiScale: Float,
    /** Human-readable for log. */
    val tableId: String
)

@Keep
object SpeedScheduledNvhGains {

    const val BIN_KMH = 5
    const val MAX_SPEED_KMH = 130

    /**
     * Knots every 5 km/h from 0..130 inclusive (27 points).
     * Values are relative scales; interpolated linearly within a bin.
     *
     * Index i = speed/5. Example: 47 km/h → between idx 9 (45) and 10 (50).
     */
    // Road boom (40–200 Hz) — lock low for subjective 悶 (cabin rec peak ~65Hz)
    // 1.2.4: raise cruise low/total so broadband + notch both push boom
    private val ROAD_LOW = floatArrayOf(
        /*0*/ 0.14f, /*5*/ 0.16f, /*10*/ 0.24f, /*15*/ 0.50f, /*20*/ 0.78f,
        /*25*/ 0.95f, /*30*/ 1.08f, /*35*/ 1.18f, /*40*/ 1.26f, /*45*/ 1.30f,
        /*50*/ 1.34f, /*55*/ 1.34f, /*60*/ 1.32f, /*65*/ 1.30f, /*70*/ 1.28f,
        /*75*/ 1.26f, /*80*/ 1.22f, /*85*/ 1.18f, /*90*/ 1.14f, /*95*/ 1.12f,
        /*100*/ 1.10f, /*105*/ 1.06f, /*110*/ 1.04f, /*115*/ 1.02f, /*120*/ 1.00f,
        /*125*/ 0.98f, /*130*/ 0.96f
    )
    private val ROAD_MID = floatArrayOf(
        0.04f, 0.04f, 0.05f, 0.07f, 0.09f,
        0.10f, 0.12f, 0.13f, 0.14f, 0.14f,
        0.14f, 0.13f, 0.12f, 0.12f, 0.11f,
        0.10f, 0.10f, 0.09f, 0.09f, 0.08f,
        0.08f, 0.07f, 0.07f, 0.07f, 0.06f,
        0.06f, 0.06f
    )
    private val ROAD_TOTAL = floatArrayOf(
        0.28f, 0.32f, 0.42f, 0.62f, 0.82f,
        0.98f, 1.08f, 1.16f, 1.22f, 1.26f,
        1.28f, 1.28f, 1.26f, 1.24f, 1.20f,
        1.16f, 1.14f, 1.12f, 1.08f, 1.05f,
        1.02f, 1.00f, 0.98f, 0.96f, 0.94f,
        0.92f, 0.90f
    )

    // Tire tread / mid-low (80–350) — easier mid weapon when classifier hits TIRE
    private val TIRE_LOW = floatArrayOf(
        0.12f, 0.14f, 0.16f, 0.32f, 0.52f,
        0.70f, 0.85f, 0.95f, 1.02f, 1.08f,
        1.10f, 1.12f, 1.12f, 1.10f, 1.08f,
        1.05f, 1.02f, 1.00f, 0.98f, 0.96f,
        0.94f, 0.92f, 0.90f, 0.88f, 0.86f,
        0.85f, 0.84f
    )
    private val TIRE_MID = floatArrayOf(
        0.06f, 0.07f, 0.10f, 0.18f, 0.30f,
        0.45f, 0.55f, 0.62f, 0.68f, 0.72f,
        0.74f, 0.75f, 0.74f, 0.72f, 0.68f,
        0.64f, 0.60f, 0.55f, 0.52f, 0.48f,
        0.45f, 0.42f, 0.40f, 0.38f, 0.36f,
        0.34f, 0.32f
    )
    private val TIRE_TOTAL = floatArrayOf(
        0.25f, 0.28f, 0.34f, 0.52f, 0.68f,
        0.82f, 0.95f, 1.02f, 1.08f, 1.12f,
        1.14f, 1.15f, 1.15f, 1.12f, 1.10f,
        1.06f, 1.04f, 1.02f, 1.00f, 0.98f,
        0.96f, 0.94f, 0.92f, 0.90f, 0.88f,
        0.86f, 0.85f
    )

    // Wind shear: ACTIVE chase — raise mid/high/total with speed (highway aero). Product: 压下去.
    private val WIND_LOW = floatArrayOf(
        0.20f, 0.22f, 0.25f, 0.35f, 0.45f,
        0.55f, 0.62f, 0.70f, 0.78f, 0.85f,
        0.90f, 0.95f, 1.00f, 1.05f, 1.08f,
        1.10f, 1.12f, 1.12f, 1.10f, 1.08f,
        1.05f, 1.02f, 1.00f, 0.98f, 0.96f,
        0.95f, 0.94f
    )
    private val WIND_MID = floatArrayOf(
        0.08f, 0.10f, 0.12f, 0.18f, 0.25f,
        0.32f, 0.40f, 0.48f, 0.55f, 0.62f,
        0.68f, 0.72f, 0.75f, 0.78f, 0.80f,
        0.82f, 0.82f, 0.80f, 0.78f, 0.75f,
        0.72f, 0.70f, 0.68f, 0.65f, 0.62f,
        0.60f, 0.58f
    )
    /** High-band schedule — only applied for WIND (other foci keep high≈0 at apply site). */
    private val WIND_HIGH = floatArrayOf(
        0.06f, 0.08f, 0.10f, 0.15f, 0.22f,
        0.30f, 0.38f, 0.48f, 0.55f, 0.62f,
        0.68f, 0.74f, 0.78f, 0.82f, 0.85f,
        0.88f, 0.90f, 0.90f, 0.88f, 0.85f,
        0.82f, 0.78f, 0.75f, 0.72f, 0.70f,
        0.68f, 0.65f
    )
    private val WIND_TOTAL = floatArrayOf(
        0.42f, 0.48f, 0.55f, 0.65f, 0.75f,
        0.85f, 0.95f, 1.02f, 1.08f, 1.12f,
        1.15f, 1.16f, 1.18f, 1.18f, 1.15f,
        1.12f, 1.10f, 1.08f, 1.06f, 1.04f,
        1.02f, 1.00f, 0.98f, 0.96f, 0.95f,
        0.94f, 0.93f
    )

    // Mixed cabin: between road and tire, HF muted
    private val MIXED_LOW = floatArrayOf(
        0.12f, 0.14f, 0.17f, 0.32f, 0.50f,
        0.68f, 0.82f, 0.92f, 1.00f, 1.05f,
        1.08f, 1.10f, 1.10f, 1.08f, 1.05f,
        1.02f, 1.00f, 0.98f, 0.96f, 0.94f,
        0.92f, 0.90f, 0.88f, 0.86f, 0.85f,
        0.84f, 0.82f
    )
    private val MIXED_MID = floatArrayOf(
        0.05f, 0.05f, 0.07f, 0.12f, 0.18f,
        0.25f, 0.32f, 0.38f, 0.42f, 0.44f,
        0.45f, 0.45f, 0.44f, 0.42f, 0.40f,
        0.38f, 0.35f, 0.32f, 0.30f, 0.28f,
        0.26f, 0.24f, 0.22f, 0.20f, 0.20f,
        0.18f, 0.18f
    )
    private val MIXED_TOTAL = floatArrayOf(
        0.25f, 0.28f, 0.33f, 0.48f, 0.62f,
        0.75f, 0.88f, 0.95f, 1.00f, 1.04f,
        1.06f, 1.08f, 1.08f, 1.06f, 1.04f,
        1.02f, 1.00f, 0.98f, 0.96f, 0.94f,
        0.92f, 0.90f, 0.88f, 0.86f, 0.85f,
        0.84f, 0.82f
    )

    // Idle / invalid speed: quiet — do not blast anti
    private val IDLE_LOW = floatArrayOf(
        0.15f, 0.15f, 0.16f, 0.20f, 0.25f,
        0.30f, 0.35f, 0.40f, 0.45f, 0.50f,
        0.55f, 0.55f, 0.55f, 0.55f, 0.55f,
        0.55f, 0.55f, 0.55f, 0.55f, 0.55f,
        0.55f, 0.55f, 0.55f, 0.55f, 0.55f,
        0.55f, 0.55f
    )
    private val IDLE_MID = floatArrayOf(
        0.04f, 0.04f, 0.04f, 0.05f, 0.05f,
        0.06f, 0.06f, 0.07f, 0.07f, 0.08f,
        0.08f, 0.08f, 0.08f, 0.08f, 0.08f,
        0.08f, 0.08f, 0.08f, 0.08f, 0.08f,
        0.08f, 0.08f, 0.08f, 0.08f, 0.08f,
        0.08f, 0.08f
    )
    private val IDLE_TOTAL = floatArrayOf(
        0.22f, 0.22f, 0.25f, 0.30f, 0.35f,
        0.40f, 0.45f, 0.50f, 0.55f, 0.58f,
        0.60f, 0.60f, 0.60f, 0.60f, 0.60f,
        0.60f, 0.60f, 0.60f, 0.60f, 0.60f,
        0.60f, 0.60f, 0.60f, 0.60f, 0.60f,
        0.60f, 0.60f
    )

    fun speedBinFloorKmh(speedKmh: Float): Int {
        val s = speedKmh.coerceIn(0f, MAX_SPEED_KMH.toFloat())
        return (floor(s / BIN_KMH.toFloat()).toInt() * BIN_KMH).coerceIn(0, MAX_SPEED_KMH)
    }

    fun gainsFor(
        focus: NvhFocusClass,
        speedKmh: Float,
        speedValid: Boolean,
        highLatency: Boolean = true
    ): SpeedNvhGainSchedule {
        val spd = if (speedValid) speedKmh.coerceAtLeast(0f) else 0f
        val (lowT, midT, totalT, tableId) = when (focus) {
            NvhFocusClass.ROAD_RUMBLE -> Quad(ROAD_LOW, ROAD_MID, ROAD_TOTAL, "road_5kmh")
            NvhFocusClass.TIRE_NOISE -> Quad(TIRE_LOW, TIRE_MID, TIRE_TOTAL, "tire_5kmh")
            NvhFocusClass.WIND_SHEAR -> Quad(WIND_LOW, WIND_MID, WIND_TOTAL, "wind_5kmh")
            NvhFocusClass.MIXED_CABIN -> Quad(MIXED_LOW, MIXED_MID, MIXED_TOTAL, "mixed_5kmh")
            NvhFocusClass.IDLE -> Quad(IDLE_LOW, IDLE_MID, IDLE_TOTAL, "idle_5kmh")
        }

        var low = lerpTable(lowT, spd)
        var mid = lerpTable(midT, spd)
        var total = lerpTable(totalT, spd)
        // Wind: schedule high-band effort. Other foci: keep high off in the table.
        var high = if (focus == NvhFocusClass.WIND_SHEAR) {
            lerpTable(WIND_HIGH, spd)
        } else {
            0f
        }

        // High latency: still **active suppress** all three product targets (not mid-kill).
        // Soft caps only limit clip risk — never zero mid for tire/road/wind.
        if (highLatency) {
            mid = when (focus) {
                NvhFocusClass.TIRE_NOISE -> min(mid, 0.78f)   // tire = mid weapon
                NvhFocusClass.ROAD_RUMBLE -> min(mid, 0.48f)  // road keeps some mid cabin
                NvhFocusClass.MIXED_CABIN -> min(mid, 0.55f)
                NvhFocusClass.WIND_SHEAR -> mid
                NvhFocusClass.IDLE -> min(mid, 0.08f)
            }
            when (focus) {
                NvhFocusClass.WIND_SHEAR -> {
                    high = min(high, 0.85f)
                    total = total.coerceAtLeast(0.85f)
                }
                NvhFocusClass.TIRE_NOISE -> {
                    total = total.coerceAtLeast(0.9f)
                    mid = mid.coerceAtLeast(0.45f)
                }
                NvhFocusClass.ROAD_RUMBLE -> {
                    total = total.coerceAtLeast(1.00f)
                    low = low.coerceAtLeast(0.95f)
                }
                else -> { }
            }
        }

        return SpeedNvhGainSchedule(
            speedBinKmh = speedBinFloorKmh(spd),
            lowGain = low.coerceIn(0f, 1.35f),
            midGain = mid.coerceIn(0f, 1.0f),
            highGain = high.coerceIn(0f, 1.0f),
            totalAntiScale = total.coerceIn(0.05f, 1.35f),
            tableId = tableId
        )
    }

    private data class Quad(
        val low: FloatArray,
        val mid: FloatArray,
        val total: FloatArray,
        val id: String
    )

    private fun lerpTable(table: FloatArray, speedKmh: Float): Float {
        val maxIdx = table.size - 1
        val s = speedKmh.coerceIn(0f, (maxIdx * BIN_KMH).toFloat())
        val f = s / BIN_KMH.toFloat()
        val i0 = floor(f).toInt().coerceIn(0, maxIdx)
        val i1 = min(i0 + 1, maxIdx)
        val t = (f - i0).coerceIn(0f, 1f)
        return table[i0] * (1f - t) + table[i1] * t
    }
}
