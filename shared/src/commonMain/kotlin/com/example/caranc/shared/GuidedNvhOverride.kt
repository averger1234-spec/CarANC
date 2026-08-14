package com.example.caranc.shared

/**
 * Guided test script can force ROAD_RUMBLE / TIRE_NOISE / WIND_SHEAR so notches +
 * speed tables match the step (classifier alone often sticks on ROAD).
 *
 * Note: avoid `kotlin.jvm.Volatile` — not available on native (iOS) targets.
 */
object GuidedNvhOverride {
    @kotlin.concurrent.Volatile
    var forcedFocusName: String? = null

    fun set(name: String?) {
        forcedFocusName = if (name.isNullOrBlank() ||
            name.equals("none", true) ||
            name.equals("auto", true)
        ) {
            null
        } else {
            name.trim().uppercase()
        }
    }

    fun clear() {
        forcedFocusName = null
    }
}
