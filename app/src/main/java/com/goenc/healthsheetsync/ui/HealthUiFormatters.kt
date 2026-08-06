package com.goenc.healthsheetsync.ui

import androidx.compose.ui.graphics.Color
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthConnectAvailability
import com.goenc.healthsheetsync.health.InvalidatedGraphRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.PermissionState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.PriorityQueue
import java.util.Locale
import kotlin.math.roundToInt

internal fun HealthConnectAvailability.displayText(): String {
    return when (this) {
        HealthConnectAvailability.Checking -> "確認中"
        HealthConnectAvailability.Available -> "利用可能"
        is HealthConnectAvailability.Unavailable -> reason
    }
}

internal fun PermissionState.displayText(): String {
    return when (this) {
        PermissionState.Unknown -> "不明"
        is PermissionState.Granted -> "許可済み ($grantedCount/$requiredCount)"
        is PermissionState.Missing ->
            "不足 ($grantedCount/$requiredCount): ${missingPermissions.joinToString { it.toPermissionLabel() }}"
    }
}

internal fun String.toPermissionLabel(): String {
    return when {
        contains("READ_WEIGHT") -> "体重の読み取り"
        contains("READ_BLOOD_GLUCOSE") -> "血糖値の読み取り"
        contains("READ_STEPS") -> "歩数の読み取り"
        else -> this
    }
}

internal fun LocalDateTime.formatDateTime(): String =
    format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

internal fun formatIntegerWithGrouping(value: Long): String =
    String.format(Locale.US, "%,d", value)

internal fun formatIntegerWithGrouping(value: Int): String =
    String.format(Locale.US, "%,d", value)

internal fun formatPal(value: Double): String =
    String.format(Locale.US, "%.3f", value)

internal fun LocalDate.formatMonthDayWithWeekday(): String =
    "${monthValue}月${dayOfMonth}日(${dayOfWeek.japaneseShortName()})"

internal fun DayOfWeek.japaneseShortName(): String {
    return when (this) {
        DayOfWeek.MONDAY -> "月"
        DayOfWeek.TUESDAY -> "火"
        DayOfWeek.WEDNESDAY -> "水"
        DayOfWeek.THURSDAY -> "木"
        DayOfWeek.FRIDAY -> "金"
        DayOfWeek.SATURDAY -> "土"
        DayOfWeek.SUNDAY -> "日"
    }
}

internal fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(DATE_PICKER_ZONE).toInstant().toEpochMilli()

internal fun Long.toLocalDateFromEpochMillis(): LocalDate =
    Instant.ofEpochMilli(this).atZone(DATE_PICKER_ZONE).toLocalDate()

internal fun ManualRecordType.inputLabels(): Pair<String, String?> {
    return when (this) {
        ManualRecordType.Weight -> "体重 kg" to null
        ManualRecordType.Steps -> "歩数" to null
        ManualRecordType.BloodGlucose -> "血糖値 mg/dL" to null
        ManualRecordType.BloodPressure -> "収縮期 mmHg" to "拡張期 mmHg"
        ManualRecordType.Waist -> "腹囲 cm" to null
        ManualRecordType.A1c -> "A1c %" to null
    }
}

internal fun ManualRecordType.formatManualValue(primaryValue: String, secondaryValue: String): String? {
    val primary = primaryValue.trim()
    val secondary = secondaryValue.trim()
    return when (this) {
        ManualRecordType.Weight ->
            primary.toDoubleOrNull()?.let { "${formatDecimal(it)} kg" }
        ManualRecordType.Steps ->
            primary.toLongOrNull()?.let { "${it}歩" }
        ManualRecordType.BloodGlucose ->
            primary.toDoubleOrNull()?.let { "${formatDecimal(it)} mg/dL" }
        ManualRecordType.BloodPressure -> {
            val systolic = primary.toLongOrNull()
            val diastolic = secondary.toLongOrNull()
            if (systolic == null || diastolic == null) null else "$systolic/$diastolic mmHg"
        }
        ManualRecordType.Waist ->
            primary.toDoubleOrNull()?.let { "${formatDecimal(it)} cm" }
        ManualRecordType.A1c ->
            primary.toDoubleOrNull()?.let { "${formatDecimal(it)} %" }
    }
}

internal fun ManualRecordType.manualInputTimeBandOptions(): List<String> {
    return when (this) {
        ManualRecordType.Steps,
        ManualRecordType.BloodGlucose,
        ManualRecordType.Waist,
        ManualRecordType.A1c -> emptyList()
        ManualRecordType.Weight,
        ManualRecordType.BloodPressure -> listOf("朝", "夜")
    }
}

internal fun ManualRecordType.defaultManualTimeBand(
    currentInstant: Instant = Instant.now(),
    zoneId: ZoneId = MANUAL_INPUT_ZONE,
): String =
    defaultManualTimeBand(LocalDateTime.ofInstant(currentInstant, zoneId).toLocalTime())

internal fun ManualRecordType.defaultManualTimeBand(currentTime: LocalTime): String {
    return when (this) {
        ManualRecordType.Weight,
        ManualRecordType.BloodPressure -> {
            val hour = currentTime.hour
            if (hour in 2..16) "朝" else "夜"
        }
        else -> "朝"
    }
}

internal fun ManualRecordType.manualInputTime(timeBand: String): LocalTime {
    return when (this) {
        ManualRecordType.Steps,
        ManualRecordType.BloodGlucose -> LocalTime.of(7, 0)
        ManualRecordType.Waist,
        ManualRecordType.A1c -> LocalTime.NOON
        else -> when (timeBand) {
            "朝" -> LocalTime.of(7, 0)
            else -> LocalTime.of(20, 0)
        }
    }
}

internal fun ManualRecordType.toGraphDataItems(
    weightRecords: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    glucoseRecords: List<DebugGlucoseRecord>,
    manualRecords: List<ManualHealthRecord>,
    invalidatedRecords: List<InvalidatedGraphRecord>,
    limit: Int? = null,
): List<GraphDataItem> {
    val activeItems = when (this) {
        ManualRecordType.Weight -> weightRecords
            .recentFirst(limit, compareByDescending<DebugWeightRecord> { it.measuredAt })
            .map { record ->
                GraphDataItem(
                    recordType = "weight",
                    uniqueKey = record.weightUniqueKey(),
                    text = "${record.measuredAt.formatDateTime()}  ${formatDecimal(record.weightKg)} kg / ${record.timeBand}",
                    measuredAt = record.measuredAt,
                    invalidatedAt = null,
                )
            }
        ManualRecordType.Steps -> dailySteps
            .recentFirst(limit, compareByDescending<DebugStepDaily> { it.targetDate })
            .map { steps ->
                GraphDataItem(
                    recordType = "steps",
                    uniqueKey = steps.targetDate.toString(),
                    text = "${steps.targetDate}  ${steps.steps}歩",
                    measuredAt = steps.targetDate.atStartOfDay(),
                    invalidatedAt = null,
                )
            }
        ManualRecordType.BloodGlucose -> glucoseRecords
            .recentFirst(limit, compareByDescending<DebugGlucoseRecord> { it.measuredAt })
            .map { record ->
                GraphDataItem(
                    recordType = "glucose",
                    uniqueKey = record.glucoseUniqueKey(),
                    text = "${record.measuredAt.formatDateTime()}  ${formatDecimal(record.bloodGlucoseMgDl)} mg/dL / ${record.mealRelation}",
                    measuredAt = record.measuredAt,
                    invalidatedAt = null,
                )
            }
        ManualRecordType.BloodPressure,
        ManualRecordType.Waist,
        ManualRecordType.A1c -> manualRecords
            .filter { it.type == this }
            .recentFirst(limit, compareByDescending<ManualHealthRecord> { it.measuredAt })
            .map { record ->
                GraphDataItem(
                    recordType = MANUAL_RECORD_TYPE,
                    uniqueKey = record.id,
                    text = "${record.measuredAt.formatDateTime()}  ${record.valueText}",
                    measuredAt = record.measuredAt,
                    invalidatedAt = record.invalidatedAt,
                )
            }
    }
    val invalidatedItems = invalidatedRecords
        .filter { it.manualType == this }
        .recentFirst(limit, compareByDescending<InvalidatedGraphRecord> { it.measuredAt })
        .map { record ->
            GraphDataItem(
                recordType = record.recordType,
                uniqueKey = record.uniqueKey,
                text = record.text,
                measuredAt = record.measuredAt,
                invalidatedAt = record.invalidatedAt,
            )
        }
    return (activeItems + invalidatedItems)
        .recentFirst(limit, compareByDescending<GraphDataItem> { it.measuredAt })
}

internal fun buildManualDataItemsByType(
    weightRecords: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    glucoseRecords: List<DebugGlucoseRecord>,
    manualRecords: List<ManualHealthRecord>,
    invalidatedRecords: List<InvalidatedGraphRecord>,
    limit: Int = MANUAL_LIST_RECENT_LIMIT,
): Map<ManualRecordType, List<GraphDataItem>> {
    return ManualRecordType.entries.associateWith { type ->
        type.toGraphDataItems(
            weightRecords = weightRecords,
            dailySteps = dailySteps,
            glucoseRecords = glucoseRecords,
            manualRecords = manualRecords,
            invalidatedRecords = invalidatedRecords,
            limit = limit,
        )
    }
}

private fun <T> List<T>.recentFirst(limit: Int?, newestFirst: Comparator<T>): List<T> {
    if (limit == null) return sortedWith(newestFirst)
    if (limit <= 0) return emptyList()

    val displayComparator = Comparator<IndexedValue<T>> { left, right ->
        newestFirst.compare(left.value, right.value)
            .takeIf { it != 0 }
            ?: left.index.compareTo(right.index)
    }
    if (size <= limit) {
        return mapIndexed { index, value -> IndexedValue(index, value) }
            .sortedWith(displayComparator)
            .map { it.value }
    }

    val worstFirst = Comparator<IndexedValue<T>> { left, right ->
        -displayComparator.compare(left, right)
    }
    val recentItems = PriorityQueue(worstFirst)
    forEachIndexed { index, value ->
        val item = IndexedValue(index, value)
        if (recentItems.size < limit) {
            recentItems.add(item)
        } else if (displayComparator.compare(item, recentItems.peek()) < 0) {
            recentItems.poll()
            recentItems.add(item)
        }
    }
    return recentItems
        .toList()
        .sortedWith(displayComparator)
        .map { it.value }
}

private data class IndexedValue<T>(
    val index: Int,
    val value: T,
)

internal data class GraphDataItem(
    val recordType: String,
    val uniqueKey: String,
    val text: String,
    val measuredAt: LocalDateTime,
    val invalidatedAt: LocalDateTime?,
)

internal fun DebugWeightRecord.weightUniqueKey(): String {
    return stableHealthRecordKey("weight", healthConnectId)
        ?: "weight|$measuredAt|$sourcePackageName|$weightKg"
}

internal fun DebugGlucoseRecord.glucoseUniqueKey(): String {
    return stableHealthRecordKey("glucose", healthConnectId)
        ?: "glucose|$measuredAt|$sourcePackageName|$bloodGlucoseMgDl|$mealRelation"
}

internal fun stableHealthRecordKey(recordType: String, healthConnectId: String): String? {
    if (healthConnectId.isBlank() || healthConnectId == UNKNOWN_HEALTH_VALUE) return null
    return "$recordType|$healthConnectId"
}

internal fun formatDecimal(value: Double): String {
    val roundedOneDecimal = (value * 10.0).roundToInt() / 10.0
    return if (roundedOneDecimal % 1.0 == 0.0) {
        roundedOneDecimal.toInt().toString()
    } else {
        roundedOneDecimal.toString()
    }
}

internal fun List<ManualHealthRecord>.latestManualValue(type: ManualRecordType): String? {
    return filter { it.type == type && it.invalidatedAt == null }
        .maxByOrNull { it.measuredAt }
        ?.valueText
}

internal fun List<ManualHealthRecord>.latestDailyBloodPressureAverageText(): String? {
    return filter { it.type == ManualRecordType.BloodPressure && it.invalidatedAt == null }
        .groupBy { it.measuredAt.toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .values
        .firstNotNullOfOrNull { records ->
            records.averageBloodPressureValue()
                ?.let { "${it.systolic.roundToInt()}/${it.diastolic.roundToInt()}" }
        }
}

internal fun List<ManualHealthRecord>.averageBloodPressureValue(): BloodPressureAverage? {
    val values = listOfNotNull(
        latestBloodPressureInTimeBand("朝"),
        latestBloodPressureInTimeBand("夜"),
    )
    if (values.isEmpty()) return null
    return BloodPressureAverage(
        systolic = values.sumOf { it.systolic } / values.size.toDouble(),
        diastolic = values.sumOf { it.diastolic } / values.size.toDouble(),
    )
}

internal fun List<ManualHealthRecord>.latestBloodPressureInTimeBand(timeBand: String): BloodPressureValue? {
    return filter { it.measuredAt.toTimeBand() == timeBand }
        .maxByOrNull { it.measuredAt }
        ?.valueText
        ?.toBloodPressureValue()
}

internal fun String.toBloodPressureValue(): BloodPressureValue? {
    val values = removeSuffix(" mmHg").split("/")
    if (values.size != 2) return null
    return BloodPressureValue(
        systolic = values[0].trim().toIntOrNull() ?: return null,
        diastolic = values[1].trim().toIntOrNull() ?: return null,
    )
}

internal fun LocalDateTime.toTimeBand(): String {
    return when (hour) {
        in 4..11 -> "朝"
        in 12..17 -> "昼"
        else -> "夜"
    }
}

internal data class BloodPressureValue(
    val systolic: Int,
    val diastolic: Int,
)

internal data class BloodPressureAverage(
    val systolic: Double,
    val diastolic: Double,
)

internal const val TAG = "HealthSheetSync"
internal const val STEP_CHART_MAX_STEPS = 30_000f
internal const val STEP_REFERENCE_STEPS = 10_000f
internal const val STEP_REFERENCE_CLEARANCE_DP = 18
internal const val CHART_LEFT_PADDING_DP = 44
internal const val CHART_RIGHT_PADDING_DP = 36
internal const val CHART_TIME_BAND_MORNING = 0
internal const val CHART_TIME_BAND_NIGHT = 1
internal const val CHART_TIME_BAND_COUNT = 2
internal const val CHART_WEIGHT_LOWER_PADDING_KG = 1.0
internal const val CHART_WEIGHT_UPPER_PADDING_KG = 1.5
internal const val CHART_EMPTY_EDGE_PADDING_DAYS = 2L
internal const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
internal const val GLUCOSE_WEIGHT_COUNT = 3
internal const val GLUCOSE_CHART_VALUE_PADDING_MG_DL = 10.0
internal const val ASSUMED_VALUE_SUFFIX = "（想定）"
internal const val UNKNOWN_HEALTH_VALUE = "不明"
internal const val MANUAL_RECORD_TYPE = "manual"
internal const val MANUAL_LIST_RECENT_LIMIT = 10
internal const val DELETE_PRESS_MILLIS = 5_000L
internal const val A1C_CHART_MIN = 4.0
internal const val A1C_CHART_MAX = 14.0
internal const val WAIST_CHART_MIN_CM = 70.0
internal const val WAIST_CHART_MAX_CM = 95.0
internal const val WAIST_CHART_GUIDE_MAX_CM = 80.0
internal const val WAIST_CHART_TOP_RATIO = 0.46f
internal const val STEP_REFERENCE_TOP_RATIO = 2f / 3f
internal const val BLOOD_PRESSURE_CHART_MIN = 70.0
internal const val BLOOD_PRESSURE_CHART_MAX = 140.0
internal const val BLOOD_PRESSURE_CHART_HEIGHT_RATIO = 0.42f
internal val DATE_PICKER_ZONE: ZoneId = ZoneId.of("UTC")
private val MANUAL_INPUT_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
internal val GLUCOSE_RECENT_WEIGHTS = listOf(0.5, 0.3, 0.2)
internal val WAIST_CHART_LINES_CM = listOf(WAIST_CHART_MIN_CM, WAIST_CHART_GUIDE_MAX_CM)
internal val BLOOD_PRESSURE_CHART_LINES = listOf(70.0, 90.0, 110.0, 140.0)
internal val AppBackground = Color(0xFFFAFAFC)
internal val HeaderBackground = Color(0xFFF2F2F3)
internal val AppText = Color(0xFF202128)
internal val AppMutedBlue = Color(0xFF4C6399)
internal val AppPrimary = Color(0xFF4B629B)
internal val DividerColor = Color(0xFFE5E5EA)
internal val ChartBlue = Color(0xFF07577D)
internal val ChartGrid = Color(0xFFE4E4E4)
internal val ChartTrend = Color(0xFF8F8F8F)
internal val ChartMovingAverage = Color(0xFFB33A7D)
internal val ChartSummary = Color(0xFF043C5A)
internal val ChartStepBar = Color(0x337E57B2)
internal val ChartStepText = Color(0xFF5E3F91)
internal val ChartGlucose = Color(0xFFC33A2B)
internal val ChartA1c = Color(0xFFD26A00)
internal val ChartWaist = Color(0xFF00897B)
internal val ChartBloodPressureSystolic = Color(0xFFB00020)
internal val ChartBloodPressureDiastolic = Color(0xFF7B3F98)
internal val ChartBloodPressureArea = Color(0x22B00020)
internal val DeleteOrange = Color(0xFFD26A00)
internal val ChartLabel = Color(0xFF7D7D84)
internal val ChartMissingPoint = Color(0xFFB0B0B0)
internal val PopupBackground = Color(0xF7FFFFFF)
