package com.goenc.healthsheetsync.health

import java.time.LocalDateTime

internal fun LocalDateTime.toHealthTimeBand(): String {
    return when (hour) {
        in 4..11 -> "朝"
        in 12..17 -> "昼"
        else -> "夜"
    }
}
