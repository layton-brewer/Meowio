package com.nuvio.app.features.insights

import java.util.TimeZone

internal actual fun insightsUtcOffsetMs(nowMs: Long): Long = TimeZone.getDefault().getOffset(nowMs).toLong()
