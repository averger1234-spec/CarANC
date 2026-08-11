package com.example.caranc.shared

/** KMP-safe float format (no String.format on Native without stdlib jvm). */
internal fun formatFloat(value: Float, decimals: Int = 4): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    val rounded = kotlin.math.round(value.toDouble() * factor) / factor
    val s = rounded.toString()
    // trim trailing junk
    return s
}

internal fun formatFloat0(value: Float): String =
    kotlin.math.round(value).toInt().toString()
