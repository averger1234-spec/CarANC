package com.example.caranc.shared.model

import com.example.caranc.shared.Keep

/**
 * Product focus: **輪噪 / 路噪 / 風切** only (cabin NVH while driving).
 *
 * - **路噪 road**: ~40–200 Hz structure + cabin boom → IMU + road model + low LMS/FF
 * - **輪噪 tire**: ~80–350 Hz tread/structure → mid-low + bank
 * - **風切 wind**: typically **>500 Hz** aero — product requires **active suppression** (mid/high LMS +
 *   wind speed schedule), not "do-not-chase". Stability via leakage/clip/freeze in the processor.
 */
@Keep
enum class NvhFocusClass {
    /** Low-frequency road boom / structure. */
    ROAD_RUMBLE,
    /** Tire tread / mid-low structure (incl. 200–350 Hz class). */
    TIRE_NOISE,
    /** High-speed aero / wind hiss — active cancel target (mid/high). */
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
    /** High-band priority (wind > 0 for active HF chase). */
    val highPriority: Float,
    /** If true, mute HF anti (idle / non-wind). Wind chase sets this false. */
    val suppressHighAnti: Boolean,
    /** If true, favor IMU/road FF over mic adaptive. */
    val preferStructuralFf: Boolean,
    /** Speed-scheduled gains (5 km/h bins) — primary path when mic is misaligned. */
    val speedSchedule: SpeedNvhGainSchedule = SpeedScheduledNvhGains.gainsFor(
        NvhFocusClass.IDLE, 0f, false, highLatency = true
    )
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

        // 2026-08-13 cabin rec: energy ~95% below 150Hz, peak ~65Hz → road-low is default drive focus.
        // Prior log: TIRE almost never selected (mid drowned by low) → tire cues must NOT require mid≈low.
        val pureWind =
            spd >= 60f && highRatio >= 0.55f && lowMid < 0.25f && midRatio < 0.08f
        // Easier tire: any meaningful mid + drive speed, even if low still dominates (AA mic reality).
        val tireCue =
            spd >= 35f && midRatio >= 0.018f && highRatio < 0.92f && (
                midRatio >= 0.025f ||
                    accel >= 0.45f ||
                    spd >= 50f && midRatio >= 0.018f ||
                    spd >= 70f // highway: prefer tire notch path unless pure wind
                )
        // Road boom lock: low energy or structure (recording-aligned)
        val roadCue =
            spd >= 18f && (lowRatio >= 0.03f || accel >= 0.35f || lowMid >= 0.04f)

        val base: NvhFocusResult = when {
            // Idle: not tire/road/wind product path
            !speedValid || spd < 12f -> NvhFocusResult(
                focus = NvhFocusClass.IDLE,
                confidence = 0.9f,
                targetHzLabel = "idle (no NVH target)",
                lowPriority = 0.2f,
                midPriority = 0.05f,
                highPriority = 0f,
                suppressHighAnti = true,
                preferStructuralFf = false
            )
            // Wind only when HF clearly dominates (stricter than before — avoid stealing tire/road)
            pureWind -> NvhFocusResult(
                focus = NvhFocusClass.WIND_SHEAR,
                confidence = (highRatio - 0.4f).coerceIn(0.4f, 1f),
                targetHzLabel = "wind >${WIND_LO_HZ.toInt()}Hz (active multi-notch)",
                lowPriority = 0.85f,
                midPriority = 0.7f,
                highPriority = 0.85f,
                suppressHighAnti = false,
                preferStructuralFf = false
            )
            // Tire before road when mid present (so 200–350 path + 3-notch engage)
            tireCue -> NvhFocusResult(
                focus = NvhFocusClass.TIRE_NOISE,
                confidence = (midRatio * 4f + accel / 4f + (spd / 200f)).coerceIn(0.35f, 1f),
                targetHzLabel = "tire ${TIRE_LO_HZ.toInt()}-${TIRE_HI_HZ.toInt()}Hz (active mid+notch)",
                lowPriority = 1.05f, // keep boom cancel while tire notches run
                midPriority = if (highLat) 0.82f else 0.95f,
                highPriority = 0f,
                suppressHighAnti = true,
                preferStructuralFf = true
            )
            // Road rumble: lock low (cabin boom ~50–120Hz from field recording)
            roadCue -> NvhFocusResult(
                focus = NvhFocusClass.ROAD_RUMBLE,
                confidence = (lowRatio * 3f + accel / 4f).coerceIn(0.35f, 1f),
                targetHzLabel = "road ${ROAD_LO_HZ.toInt()}-${ROAD_HI_HZ.toInt()}Hz (lock low boom)",
                lowPriority = 1.15f,
                midPriority = if (highLat) 0.22f else 0.4f, // lock low: less mid bleed
                highPriority = 0f,
                suppressHighAnti = true,
                preferStructuralFf = true
            )
            // Driving but unclear → treat as road-low (recording prior), not wind
            else -> NvhFocusResult(
                focus = NvhFocusClass.ROAD_RUMBLE,
                confidence = 0.35f,
                targetHzLabel = "road default (low-lock prior)",
                lowPriority = 1.05f,
                midPriority = if (highLat) 0.2f else 0.35f,
                highPriority = 0f,
                suppressHighAnti = true,
                preferStructuralFf = true
            )
        }

        // Attach 5 km/h speed schedule (primary gain path when mic is misaligned)
        val schedule = SpeedScheduledNvhGains.gainsFor(
            focus = base.focus,
            speedKmh = spd,
            speedValid = speedValid && spd >= 0f,
            highLatency = highLat
        )
        return base.copy(
            // Priorities follow speed table (mic-independent product core)
            lowPriority = schedule.lowGain,
            midPriority = schedule.midGain,
            highPriority = schedule.highGain,
            speedSchedule = schedule
        )
    }

    /**
     * Scale band gains: base dominant-band shape × speed-scheduled NVH priorities.
     * Wind: allow full high priority (active chase). Other foci: suppressHigh keeps high at 0.
     */
    fun applyToBandGains(base: BandGains, nvh: NvhFocusResult): BandGains {
        val highBase = if (nvh.focus == NvhFocusClass.WIND_SHEAR) {
            // Ensure wind has headroom even if classifier base.high was 0
            maxOf(base.high, 0.55f)
        } else {
            base.high
        }
        return BandGains(
            low = (base.low * nvh.lowPriority).coerceIn(0f, 1.35f),
            mid = (base.mid * nvh.midPriority).coerceIn(0f, 1.1f),
            high = if (nvh.suppressHighAnti) {
                0f
            } else {
                (highBase * nvh.highPriority.coerceAtLeast(0.15f)).coerceIn(0f, 1.0f)
            }
        )
    }
}
