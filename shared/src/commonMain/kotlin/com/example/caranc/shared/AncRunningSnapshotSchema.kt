package com.example.caranc.shared

/**
 * **Single source of truth for running_snapshot field names** (Android experience → iOS iteration).
 *
 * - Android [com.example.caranc.shared.service.AudioEngine] emits these in JSONL.
 * - iOS `SessionLogger` / future KMP bridge MUST use the same keys so road-test logs are comparable.
 * - Values may be computed differently on iOS MVP; missing capability → write `"n/a"` or omit only if
 *   explicitly documented as platform-only. Prefer emitting the key with `"n/a"`.
 *
 * Schema version: bump [SCHEMA_VERSION] when adding/renaming keys.
 */
object AncRunningSnapshotSchema {
    const val SCHEMA_VERSION = "1"
    const val PHASE = "running_snapshot"

    // --- Core road-test KPI (must exist on both platforms) ---
    const val RAW_DB = "rawDb"
    const val ANTI_NOISE_DB = "antiNoiseDb"          // Android name; iOS aliases antiDb → this key
    const val CANCELLED_DB = "cancelledDb"
    const val REDUCTION_DB = "reductionDb"
    const val REDUCTION_DB_LEGACY = "reductionDbLegacy"
    const val LOW_BAND_RUMBLE_REDUCTION = "lowBandRumbleReduction"
    const val PRIMARY_REDUCTION_KPI = "primaryReductionKpi"

    // Closed-loop / plant (Android full; iOS may be n/a until plant residual port)
    const val RAW_LOW_BAND_DB = "rawLowBandDb"
    const val RESIDUAL_LOW_BAND_DB = "residualLowBandDb"
    const val PLANT_RESIDUAL_LOW_BAND_DB = "plantResidualLowBandDb"
    const val PLANT_RESIDUAL_REDUCTION_DB = "plantResidualReductionDb"
    const val BAND_E60_DB = "bandE60Db"
    const val BAND_E80_DB = "bandE80Db"
    const val BAND_E100_DB = "bandE100Db"
    const val BAND_E120_DB = "bandE120Db"
    const val OUTPUT_PATH_ACTIVE = "outputPathActive"
    const val PLANT_DELAY_FOR_RESIDUAL = "plantDelayForResidual"

    // NVH product focus
    const val NVH_FOCUS = "nvhFocus"
    const val NVH_TARGET_HZ = "nvhTargetHz"
    // Speed-scheduled gains (5 km/h bins — road/tire/wind tables)
    const val SPEED_NVH_BIN_KMH = "speedNvhBinKmh"
    const val SPEED_NVH_LOW_GAIN = "speedNvhLowGain"
    const val SPEED_NVH_MID_GAIN = "speedNvhMidGain"
    const val SPEED_NVH_TOTAL_ANTI = "speedNvhTotalAnti"
    const val SPEED_NVH_TABLE_ID = "speedNvhTableId"
    // Tire 3-notch + wind 6-notch (1.2.3) + road boom (1.2.5)
    const val TIRE_NOTCH_ENERGY = "tireNotchEnergy"
    const val WIND_NOTCH_ENERGY = "windNotchEnergy"
    const val TIRE_NOTCH_F0_HZ = "tireNotchF0Hz"
    const val WIND_NOTCH_ACTIVE_COUNT = "windNotchActiveCount"
    const val NOTCH_MIX_ANTI = "notchMixAnti"
    /** Adaptive road boom energy (weight-gated complex LMS). */
    const val ROAD_NOTCH_ENERGY = "roadNotchEnergy"
    /** Sum |w| on boom channels — rises when phase locks (~65Hz 悶). */
    const val ROAD_BOOM_WEIGHT_ENERGY = "roadBoomWeightEnergy"

    // Vehicle / IMU
    const val VEHICLE_SPEED_KMH = "vehicleSpeedKmh"
    const val VEHICLE_SPEED_VALID = "vehicleSpeedValid"
    const val IS_DRIVING_RUMBLE = "isDrivingRumble"
    const val RUMBLE_ACCEL = "rumbleAccel"           // linearAccelMagnitude proxy
    const val ROAD_ROUGHNESS = "roadRoughness"

    // Latency
    const val ESTIMATED_LATENCY_MS = "estimatedLatencyMs"
    const val MEASURED_LATENCY_MS = "measuredLatencyMs"
    const val MAX_CANCEL_FREQUENCY_HZ = "maxCancelFrequencyHz"
    const val LATENCY_MID_ENABLED = "latencyMidEnabled"
    const val LATENCY_HIGH_ENABLED = "latencyHighEnabled"
    const val LATENCY_STRATEGY = "latencyStrategy"
    const val PLANT_ELECTRICAL_DELAY_SAMPLES = "plantElectricalDelaySamples"

    // Learning / DSP path
    const val LMS_LOW_UPDATES = "lmsLowUpdates"
    const val LMS_MID_UPDATES = "lmsMidUpdates"
    const val WEIGHT_FROZEN = "weightFrozen"
    const val PROCESSING_MODE = "processingMode"
    const val TIER = "tier"
    const val EFFECTIVE_LOW_MU = "effectiveLowMu"
    const val EFFECTIVE_MID_MU = "effectiveMidMu"

    // Literature / bank (Android; iOS n/a until KMP framework wired)
    const val IMU_MIC_COHERENCE = "imuMicCoherence"
    const val BANK_MATCH_QUALITY = "bankMatchQuality"
    const val FIXED_BANK_OUT = "fixedBankOut"
    const val NEURAL_LATENT_ENABLED = "neuralLatentEnabled"
    const val FDAF_DELAYLESS = "fdafDelayless"

    // Route / platform
    const val AUDIO_BACKEND = "audioBackend"
    const val AA_LINK_TYPE = "aaLinkType"
    const val WIRELESS_AA_SUSPECTED = "wirelessAaSuspected"
    const val PLATFORM = "platform"
    const val SCHEMA_VERSION_KEY = "snapshotSchemaVersion"

    // Guided test
    const val GUIDED_TEST_STEP_ID = "guidedTestStepId"
    const val GUIDED_TEST_ACTIVE = "guidedTestActive"

    /** Keys every road-test export should contain (iOS fills n/a when unavailable). */
    val REQUIRED_ROAD_TEST_KEYS: List<String> = listOf(
        SCHEMA_VERSION_KEY,
        PLATFORM,
        RAW_DB,
        ANTI_NOISE_DB,
        LOW_BAND_RUMBLE_REDUCTION,
        PRIMARY_REDUCTION_KPI,
        OUTPUT_PATH_ACTIVE,
        NVH_FOCUS,
        VEHICLE_SPEED_KMH,
        VEHICLE_SPEED_VALID,
        IS_DRIVING_RUMBLE,
        RUMBLE_ACCEL,
        ESTIMATED_LATENCY_MS,
        MAX_CANCEL_FREQUENCY_HZ,
        LATENCY_STRATEGY,
        TIER,
        AUDIO_BACKEND,
        LMS_LOW_UPDATES
    )
}
