package com.example.caranc.shared.test

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** iOS actual for guided test wall clock */
internal actual fun currentTimeMs(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
