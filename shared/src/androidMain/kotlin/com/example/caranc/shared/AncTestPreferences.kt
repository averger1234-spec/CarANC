package com.example.caranc.shared

import android.content.Context

object AncTestPreferences {

    private const val PREFS_NAME = "anc_test_prefs"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"
    private const val KEY_VEHICLE_MODEL = "vehicle_model"
    private const val KEY_SCENARIO = "scenario"
    private const val KEY_PHONE_PLACEMENT = "phone_placement"
    private const val KEY_CONNECTION_TYPE = "connection_type"
    private const val KEY_MANUAL_TEST_RPM = "manual_test_rpm"
    private const val KEY_OBD_DEVICE_ADDRESS = "obd_device_address"
    private const val KEY_MIMO_TRIAL_ENABLED = "mimo_trial_enabled"
    private const val KEY_FORCE_NORMAL_MODE = "force_normal_mode"
    private const val KEY_MUSIC_LOW_ANC = "music_low_anc"
    private const val KEY_USER_ANC_GAIN = "user_anc_gain"
    private const val KEY_DEBUG_LMS_MU_MULT = "debug_lms_mu_mult"
    private const val KEY_DEBUG_FREEZE_THRESHOLD = "debug_freeze_threshold"
    private const val KEY_DEBUG_FREEZE_CONSEC = "debug_freeze_consec"
    private const val KEY_DEBUG_LATENCY_OVERRIDE_MS = "debug_latency_override_ms"
    private const val KEY_DEBUG_LEAKAGE = "debug_leakage"
    private const val KEY_DEBUG_USE_NATIVE_LOW = "debug_use_native_low"
    // Personal acoustic identity: follows the *user* (phone/Google acct) not the car. Bias multiplies rumble feedforward strength.
    // E.g. user sensitive to 200-350Hz rumble sets >1.0; protects call bands differently. Future: sync via account + hearing curve.
    // Currently local; applied on top of tier auto params (no manual advanced needed).
    private const val KEY_PERSONAL_RUMBLE_BIAS = "personal_rumble_bias"

    fun isLoggingEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LOGGING_ENABLED, true)
    }

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
    }

    fun getEnvironment(context: Context): AncTestEnvironment {
        val storage = prefs(context)
        return AncTestEnvironment(
            vehicleModel = storage.getString(KEY_VEHICLE_MODEL, "Skoda Octavia 2019").orEmpty(),
            scenario = storage.getString(KEY_SCENARIO, "").orEmpty(),
            phonePlacement = storage.getString(KEY_PHONE_PLACEMENT, "").orEmpty(),
            connectionType = storage.getString(KEY_CONNECTION_TYPE, "").orEmpty()
        )
    }

    fun saveEnvironment(context: Context, environment: AncTestEnvironment) {
        prefs(context).edit()
            .putString(KEY_VEHICLE_MODEL, environment.vehicleModel)
            .putString(KEY_SCENARIO, environment.scenario)
            .putString(KEY_PHONE_PLACEMENT, environment.phonePlacement)
            .putString(KEY_CONNECTION_TYPE, environment.connectionType)
            .apply()
    }

    fun getManualTestRpm(context: Context): Float {
        return prefs(context).getFloat(KEY_MANUAL_TEST_RPM, 0f).coerceAtLeast(0f)
    }

    fun setManualTestRpm(context: Context, rpm: Float) {
        prefs(context).edit().putFloat(KEY_MANUAL_TEST_RPM, rpm.coerceAtLeast(0f)).apply()
    }

    fun getObdDeviceAddress(context: Context): String {
        return prefs(context).getString(KEY_OBD_DEVICE_ADDRESS, "").orEmpty()
    }

    fun setObdDeviceAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_OBD_DEVICE_ADDRESS, address.trim()).apply()
    }

    fun isMimoTrialEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MIMO_TRIAL_ENABLED, true)
    }

    fun setMimoTrialEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MIMO_TRIAL_ENABLED, enabled).apply()
    }

    fun isForceNormalMode(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FORCE_NORMAL_MODE, false)
    }

    fun setForceNormalMode(context: Context, force: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_NORMAL_MODE, force).apply()
    }

    fun isMusicLowAncEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MUSIC_LOW_ANC, true)
    }

    fun setMusicLowAncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUSIC_LOW_ANC, enabled).apply()
    }

    fun getUserAncGain(context: Context): Float {
        return prefs(context).getFloat(KEY_USER_ANC_GAIN, 1.0f).coerceIn(0f, 1f)
    }

    fun setUserAncGain(context: Context, gain: Float) {
        prefs(context).edit().putFloat(KEY_USER_ANC_GAIN, gain.coerceIn(0f, 1f)).apply()
    }

    fun getDebugLmsMuMultiplier(context: Context): Float {
        return prefs(context).getFloat(KEY_DEBUG_LMS_MU_MULT, 1.0f).coerceIn(0.1f, 3.0f)
    }

    fun setDebugLmsMuMultiplier(context: Context, mult: Float) {
        prefs(context).edit().putFloat(KEY_DEBUG_LMS_MU_MULT, mult.coerceIn(0.1f, 3.0f)).apply()
    }

    fun getDebugFreezeThreshold(context: Context): Float {
        return prefs(context).getFloat(KEY_DEBUG_FREEZE_THRESHOLD, 15.0f).coerceIn(8f, 25f)
    }

    fun setDebugFreezeThreshold(context: Context, threshold: Float) {
        prefs(context).edit().putFloat(KEY_DEBUG_FREEZE_THRESHOLD, threshold.coerceIn(8f, 25f)).apply()
    }

    fun getDebugFreezeConsecutive(context: Context): Int {
        return prefs(context).getInt(KEY_DEBUG_FREEZE_CONSEC, 3).coerceIn(1, 5)
    }

    fun setDebugFreezeConsecutive(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_DEBUG_FREEZE_CONSEC, count.coerceIn(1, 5)).apply()
    }

    fun getDebugLatencyOverrideMs(context: Context): Float {
        return prefs(context).getFloat(KEY_DEBUG_LATENCY_OVERRIDE_MS, 0f).coerceAtLeast(0f)
    }

    fun setDebugLatencyOverrideMs(context: Context, ms: Float) {
        prefs(context).edit().putFloat(KEY_DEBUG_LATENCY_OVERRIDE_MS, ms.coerceAtLeast(0f)).apply()
    }

    fun getDebugLeakage(context: Context): Float {
        return prefs(context).getFloat(KEY_DEBUG_LEAKAGE, 0.9998f).coerceIn(0.99f, 0.99999f)
    }

    fun setDebugLeakage(context: Context, alpha: Float) {
        prefs(context).edit().putFloat(KEY_DEBUG_LEAKAGE, alpha.coerceIn(0.99f, 0.99999f)).apply()
    }

    fun isDebugUseNativeLowBand(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DEBUG_USE_NATIVE_LOW, false)
    }

    fun setDebugUseNativeLowBand(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEBUG_USE_NATIVE_LOW, enabled).apply()
    }

    fun getPersonalRumbleBias(context: Context): Float {
        return prefs(context).getFloat(KEY_PERSONAL_RUMBLE_BIAS, 1.0f).coerceIn(0.7f, 1.3f)
    }

    fun setPersonalRumbleBias(context: Context, bias: Float) {
        prefs(context).edit().putFloat(KEY_PERSONAL_RUMBLE_BIAS, bias.coerceIn(0.7f, 1.3f)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}