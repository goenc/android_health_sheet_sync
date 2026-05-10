package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import java.time.LocalDate
import java.time.LocalDateTime

object OneTouchRevealTextParser {
    fun parse(text: String): List<DebugGlucoseRecord> {
        return (parseSharedTextBlocks(text) + parseCsvRows(text))
            .distinctBy { record ->
                "${record.measuredAt}|${record.bloodGlucoseMgDl}|${record.mealRelation}"
            }
            .sortedByDescending { it.measuredAt }
    }

    private fun parseSharedTextBlocks(text: String): List<DebugGlucoseRecord> {
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
            .toList()
    }

    private fun parseCsvRows(text: String): List<DebugGlucoseRecord> {
        val rows = text.lineSequence()
            .map { parseCsvLine(it) }
            .filter { row -> row.any { it.isNotBlank() } }
            .toList()
        if (rows.size < 2) return emptyList()

        val header = rows.first()
        return rows.drop(1).mapNotNull { row ->
            val measuredAt = row.findMeasuredAt(header) ?: return@mapNotNull null
            val glucose = row.findGlucose(header) ?: return@mapNotNull null
            val mealRelation = row.findMealRelation(header)
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

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuote && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuote = !inQuote
                char == ',' && !inQuote -> {
                    values += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        values += current.toString().trim()
        return values
    }

    private fun List<String>.findMeasuredAt(header: List<String>): LocalDateTime? {
        val joined = joinToString(" ")
        DATE_TIME_PATTERNS.forEach { pattern ->
            pattern.find(joined)?.let { match ->
                return parseMeasuredAt(
                    year = match.groupValues[1],
                    month = match.groupValues[2],
                    day = match.groupValues[3],
                    hour = match.groupValues[4],
                    minute = match.groupValues[5],
                )
            }
        }

        val dateIndex = header.indexOfFirstHeader("日付", "date")
        val timeIndex = header.indexOfFirstHeader("時刻", "time")
        if (dateIndex < 0 || timeIndex < 0) return null
        return DATE_TIME_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find("${getOrNull(dateIndex).orEmpty()} ${getOrNull(timeIndex).orEmpty()}")?.let { match ->
                parseMeasuredAt(
                    year = match.groupValues[1],
                    month = match.groupValues[2],
                    day = match.groupValues[3],
                    hour = match.groupValues[4],
                    minute = match.groupValues[5],
                )
            }
        }
    }

    private fun List<String>.findGlucose(header: List<String>): Double? {
        val glucoseIndex = header.indexOfFirstHeader("血糖", "glucose", "mg/dl")
        if (glucoseIndex >= 0) return getOrNull(glucoseIndex)?.extractNumber()
        return firstNotNullOfOrNull { value ->
            value.extractNumber()?.takeIf { it in 20.0..600.0 }
        }
    }

    private fun List<String>.findMealRelation(header: List<String>): String {
        val mealIndex = header.indexOfFirstHeader("食", "meal")
        val mealText = mealIndex.takeIf { it >= 0 }?.let { getOrNull(it) }.orEmpty()
        val rowText = joinToString(" ")
        return when {
            mealText.contains("食前") || rowText.contains("食前") -> "食前"
            mealText.contains("食後") || rowText.contains("食後") -> "食後"
            mealText.contains("空腹") || rowText.contains("空腹") -> "空腹時"
            mealText.isNotBlank() -> mealText.trimJapaneseSeparators()
            else -> UNKNOWN
        }
    }

    private fun List<String>.indexOfFirstHeader(vararg keywords: String): Int {
        return indexOfFirst { header ->
            keywords.any { keyword -> header.contains(keyword, ignoreCase = true) }
        }
    }

    private fun String.extractNumber(): Double? =
        NUMBER_PATTERN.find(this)?.value?.toDoubleOrNull()

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
    private val DATE_TIME_PATTERNS = listOf(
        Regex("""(\d{4})年\s*(\d{1,2})月\s*(\d{1,2})日[、,\s]+(\d{1,2})[:：](\d{2})"""),
        Regex("""(\d{4})[/-](\d{1,2})[/-](\d{1,2})[,\s]+(\d{1,2})[:：](\d{2})"""),
    )
    private val NUMBER_PATTERN = Regex("""\d+(?:\.\d+)?""")
}
