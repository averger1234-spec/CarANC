package com.example.caranc.shared.model

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** iOS actual for cabin profile / aging timestamps */
internal actual fun currentEpochMs(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
