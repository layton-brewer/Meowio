package com.nuvio.app.features.insights

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone

internal actual fun insightsUtcOffsetMs(nowMs: Long): Long =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate()) * 1000L
