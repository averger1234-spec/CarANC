package com.example.caranc.shared.model

import androidx.annotation.Keep

/**
 * Product focus: **輪噪 / 路噪 / 風切** only (cabin NVH while driving).
 *
 * Physics under phone+AA latency (~100–250 ms):
 * - **路噪 road**: ~40–200 Hz structure + cabin boom → IMU + road model + low LMS/FF (best ROI)
 * - **輪噪 tire**: ~80–350 Hz tread/structure (Skoda peak often 200–350) → mid-low FF + bank; phase hard above maxCancel
 * - **風切 wind**: typically **>500–800 Hz** broadband aero hiss → **do NOT chase with adaptive ANC**
 *   (causality: uncancelable at AA delay; chasing causes telegraph/chirp). Mute high adaptive; soft duck only.
 */
@Keep
enum class NvhFocusClass {
    /** Low-frequency road boom / structure. */
    ROAD_RUMBLE,
    /** Tire tread / mid-low structure (incl. 200–350 Hz class). */
    TIRE_NOISE,
    /** High-speed aero / wind hiss — protect only, no aggressive cancel. */
    WIND_SHEAR,
    /** Mixed or insufficient cues. */
    MIXED_CABIN,
    /** Parked / idle — not the product target. */
    IDLE
}

@Keep
data class NvhFocusResult(
    val focus: NvhFocusClass,
    /** 0–1 confidence for logging. */
    val confidence: Float,
    /** Human-readable target band string for UI/log. */
    val targetHzLabel: String,
    val lowPriority: Float,
    val midPriority: Float,
    /** Always near 0 under AA for wind; never boost. */
    val highPriority: Float,
    /** If true, aggressively mute HF anti (wind-chase kill). */
    val suppressHighAnti: Boolean,
    /** If true, favor IMU/road FF over mic adaptive. */
    val preferStructuralFf: Boolean
)

@Keep
object CabinNvhFocus {

    // Target engineering bands (Hz) — product contract
    const val ROAD_LO_HZ = 40f
    const val ROAD_HI_HZ = 200f
    const val TIRE_LO_HZ = 80f
    const val TIRE_HI_HZ = 350f
    const val WIND_LO_HZ = 500f

    fun classify(
        speedKmh: Float,
        speedValid: Boolean,
        lowRatio: Float,
        midRatio: Float,
        highRatio: Float,
        linearAccelMagnitude: Float,
        estimatedLatencyMs: Float
    ): NvhFocusResult {
        val spd = if (speedValid) speedKmh else 0f
        val accel = linearAccelMagnitude.coerceAtLeast(0f)
        val lowMid = (lowRatio + midRatio).coerceIn(0f, 1f)
        val highLat = estimatedLatencyMs >= 100f

        // Idle: not tire/road/wind product path
        if (!speedValid || spd < 12f) {
            return NvhFocusResult(
                focus = NvhFocusClass.IDLE,
                confidence = 0.9f,
                targetHzLabel = "idle (no NVH target)",
                lowPriority = 0.2f,
                midPriority = 0.05f,
                highPriority = 0f,
                suppressHighAnti = true,
                preferStructuralFf = false
            )
        }

        // Wind-shear: high speed + spectrum dominated by HF + little structure
        val windCue = spd >= 55f && highRatio >= 0.88f && lowMid < 0.12f && accel < 1.2f
        if (windCue) {
            return NvhFocusResult(
                focus = NvhFocusClass.WIND_SHEAR,
                confidence = (highRatio - 0.7f).coerceIn(0.4f, 1f),
                targetHzLabel = "wind >${WIND_LO_HZ.toInt()}Hz (no adaptive HF)",
                lowPriority = 0.35f,  // keep mild boom cancel only
                midPriority = 0.05f,
                highPriority = 0f,
                suppressHighAnti = true,
                preferStructuralFf = true
            )
        }

        // Tire: structure vibration + mid-band presence, or strong accel roughness
        val tireCue = spd >= 25f && (
            midRatio >= 0.04f && midRatio >= lowRatio * 0.7f ||
                accel >= 0.7f && lowMid >= 0.04f ||
                spd >= 40f && lowMid >= 0.05f && midRatio >= 0.03f
            )
        if (tireCue && !windCue) {
            val midPri = if (highLat) 0.35f else 0.7f
            return NvhFocusResult(
                focus = NvhFocusClass.TIRE_NOISE,
                confidence = (lowMid * 2f + (accel / 3f)).coerceIn(0.35f, 1f),
                targetHzLabel = "tire ${TIRE_LO_HZ.toInt()}-${TIRE_HI_HZ.toInt()}Hz",
                lowPriority = 1f,
                midPriority = midPri,
                highPriority = 0f,
                suppressHighAnti = true,
                preferStructuralFf = true
            )
        }

        // Road rumble: speed + low structure
        val roadCue = spd >= 20f && (lowRatio >= 0.04f || accel >= 0.45f || lowMid >= 0.05f)
        if (roadCue) {
            return NvhFocusResult(
                focus = NvhFocusClass.ROAD_RUMBLE,
                confidence = (lowRatio * 3f + accel / 4f).coerceIn(0.35f, 1f),
                targetHzLabel = "road ${ROAD_LO_HZ.toInt()}-${ROAD_HI_HZ.toInt()}Hz",
                lowPriority = 1f,
                midPriority = if (highLat) 0.2f else 0.4f,
                highPriority = 0f,
                suppressHighAnti = true,
                preferStructuralFf = true
            )
        }

        // Driving but unclear spectrum → still treat as mixed road/tire, never wind-chase
        return NvhFocusResult(
            focus = NvhFocusClass.MIXED_CABIN,
            confidence = 0.3f,
            targetHzLabel = "mixed tire/road (HF muted)",
            lowPriority = 0.85f,
            midPriority = if (highLat) 0.2f else 0.35f,
            highPriority = 0f,
            suppressHighAnti = true,
            preferStructuralFf = true
        )
    }

    /** Scale band gains for tire/road/wind product policy. */
    fun applyToBandGains(base: BandGains, nvh: NvhFocusResult): BandGains {
        return BandGains(
            low = (base.low * nvh.lowPriority).coerceIn(0f, 1.2f),
            mid = (base.mid * nvh.midPriority).coerceIn(0f, 1f),
            high = if (nvh.suppressHighAnti) 0f else (base.high * nvh.highPriority).coerceIn(0f, 0.2f)
        )
    }
}
