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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}