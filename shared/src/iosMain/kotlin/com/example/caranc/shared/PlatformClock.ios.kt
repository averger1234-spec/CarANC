package com.example.caranc.shared

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun platformEpochMs(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
