package com.example.caranc.shared

/** Wall clock for KMP (commonMain cannot use System.currentTimeMillis). */
internal expect fun platformEpochMs(): Long
