package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import java.time.LocalDate
import java.time.LocalDateTime

object OneTouchRevealTextParser {
    fun parse(text: String): List<DebugGlucoseRecord> {
        return GLUCOSE_BLOCK_PATTERN.findAll(text)
            .mapNotNull { match ->
                val glucose = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                val mealRelation = match.groupValues[2].trimJapaneseSeparators().ifBlank { UNKNOWN }
                val measuredAt = parseMeasuredAt(
                    year = match.groupValues[3],
                    month = match.groupValues[4],
                    day = match.groupValues[5],
                    hour = match.groupValues[6],
                    minute = match.groupValues[7],
                ) ?: return@mapNotNull null
                DebugGlucoseRecord(
                    measuredAt = measuredAt,
                    targetDate = measuredAt.toLocalDate(),
                    timeBand = measuredAt.toTimeBand(),
                    bloodGlucoseMgDl = glucose,
                    mealRelation = mealRelation,
                    healthConnectId = oneTouchId(measuredAt, glucose, mealRelation),
                    sourceAppName = SOURCE_APP_NAME,
                    sourcePackageName = SOURCE_PACKAGE_NAME,
                )
            }
            .distinctBy { record ->
                "${record.measuredAt}|${record.bloodGlucoseMgDl}|${record.mealRelation}"
            }
            .toList()
    }

    private fun parseMeasuredAt(
        year: String,
        month: String,
        day: String,
        hour: String,
        minute: String,
    ): LocalDateTime? {
        return runCatching {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
                .atTime(hour.toInt(), minute.toInt())
        }.getOrNull()
    }

    private fun LocalDateTime.toTimeBand(): String {
        return when (hour) {
            in 4..11 -> "朝"
            in 12..17 -> "昼"
            else -> "夜"
        }
    }

    private fun String.trimJapaneseSeparators(): String =
        trim().trim('、', ',', '，', ' ')

    private fun oneTouchId(
        measuredAt: LocalDateTime,
        glucose: Double,
        mealRelation: String,
    ): String = "onetouch|$measuredAt|$glucose|$mealRelation"

    private const val SOURCE_APP_NAME = "ONE TOUCH Reveal"
    private const val SOURCE_PACKAGE_NAME = "one_touch_reveal_share"
    private const val UNKNOWN = "不明"

    private val GLUCOSE_BLOCK_PATTERN = Regex(
        pattern = """血糖値[：:]\s*([0-9]+(?:\.[0-9]+)?)\s*[,，、]?\s*([^,\n，、]+).*?日付・時刻[：:]\s*(\d{4})年\s*(\d{1,2})月\s*(\d{1,2})日[、,\s]+(\d{1,2})[:：](\d{2})""",
        options = setOf(RegexOption.DOT_MATCHES_ALL),
    )
}
