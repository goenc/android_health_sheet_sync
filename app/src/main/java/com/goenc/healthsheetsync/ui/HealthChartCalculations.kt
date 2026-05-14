package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal enum class WeightChartRange(
    val label: String,
    private val startAt: (LocalDateTime) -> LocalDateTime,
    private val endAt: (LocalDateTime) -> LocalDateTime,
) {
    SixMonths("半年", { latestAt -> latestAt.minusMonths(6) }, { startAt -> startAt.plusMonths(6) }),
    OneMonth("1か月", { latestAt -> latestAt.minusMonths(1) }, { startAt -> startAt.plusMonths(1) }),
    TwoWeeks("2週間", { latestAt -> latestAt.minusWeeks(2) }, { startAt -> startAt.plusWeeks(2) }),
    OneWeek("1週間", { latestAt -> latestAt.minusWeeks(1) }, { startAt -> startAt.plusWeeks(1) });

    fun window(records: List<DebugWeightRecord>, visibleEndAt: LocalDateTime?): ChartTimeWindow? {
        val rangeEndAt = visibleEndAt ?: records.lastOrNull()?.measuredAt ?: return null
        val rangeStartAt = startAt(rangeEndAt)
        return ChartTimeWindow(
            startAt = rangeStartAt.minusDays(CHART_EMPTY_EDGE_PADDING_DAYS),
            endAt = rangeEndAt.plusDays(CHART_EMPTY_EDGE_PADDING_DAYS),
        )
    }

    fun durationAt(endAt: LocalDateTime): Duration {
        return Duration.between(startAt(endAt), endAt)
    }

    fun minimumEndAt(records: List<DebugWeightRecord>): LocalDateTime? {
        val firstAt = records.firstOrNull()?.measuredAt ?: return null
        val latestAt = records.lastOrNull()?.measuredAt ?: return null
        val endForFirstRecord = endAt(firstAt)
        return if (endForFirstRecord.isAfter(latestAt)) latestAt else endForFirstRecord
    }
}

internal data class WeightTrendLine(
    val startWeightKg: Double,
    val endWeightKg: Double,
    val slopeKgPerDay: Double,
)

internal data class ChartWeightPoint(
    val measuredAt: LocalDateTime,
    val targetDate: LocalDate,
    val timeBand: String,
    val weightKg: Double,
    val sourceCount: Int,
) {
    val isAverage: Boolean
        get() = sourceCount >= 2
}

internal data class ChartDaySelection(
    val date: LocalDate,
    val morning: ChartWeightPoint?,
    val night: ChartWeightPoint?,
    val steps: DebugStepDaily?,
) {
    val points: List<ChartWeightPoint>
        get() = listOfNotNull(morning, night)
}

internal data class ChartTimeWindow(
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
)

internal data class StepBar(
    val centerAt: LocalDateTime,
    val steps: Long,
)

internal data class FastingGlucoseChart(
    val weightedAverageMgDl: Double,
    val visibleRecords: List<DebugGlucoseRecord>,
)

internal data class A1cChart(
    val visibleRecords: List<A1cChartRecord>,
    val lineRecords: List<A1cChartRecord>,
    val latestRecord: A1cChartRecord?,
)

internal data class A1cChartRecord(
    val measuredAt: LocalDateTime,
    val value: Double,
)

internal data class WaistChart(
    val visibleRecords: List<WaistChartRecord>,
    val lineRecords: List<WaistChartRecord>,
    val latestRecord: WaistChartRecord?,
)

internal data class WaistChartRecord(
    val measuredAt: LocalDateTime,
    val value: Double,
)

internal data class BloodPressureChart(
    val visibleRecords: List<BloodPressureChartRecord>,
    val lineRecords: List<BloodPressureChartRecord>,
    val latestRecord: BloodPressureChartRecord?,
)

internal data class BloodPressureChartRecord(
    val measuredAt: LocalDateTime,
    val systolic: Double,
    val diastolic: Double,
)

internal data class SelectedGraphValues(
    val glucoseText: String?,
    val a1cText: String?,
    val bloodPressureText: String?,
    val waistText: String?,
)

internal data class GraphValue(
    val date: LocalDate,
    val value: Double,
)

internal data class ChartDateLabel(
    val date: LocalDate,
    val x: Float,
    val width: Float,
) {
    val left: Float
        get() = x - width / 2f
    val right: Float
        get() = x + width / 2f
}

internal fun ChartWeightPoint.isMorning(): Boolean =
    timeBand == "朝"

internal fun ChartWeightPoint?.weightText(): String =
    this?.let { "${formatDecimal(it.weightKg)} kg" } ?: "-"

internal fun ChartDaySelection.weightDifferenceText(): String {
    val morningWeight = morning?.weightKg ?: return "-"
    val nightWeight = night?.weightKg ?: return "-"
    return "${formatDecimal(abs(nightWeight - morningWeight))} kg"
}

internal fun List<DebugWeightRecord>.weightTextFor(timeBand: String): String {
    return filter { it.timeBand == timeBand }
        .maxByOrNull { it.measuredAt }
        ?.let { "${formatDecimal(it.weightKg)}kg" }
        ?: "-"
}

internal fun List<DebugWeightRecord>.toChartWeightPoints(): List<ChartWeightPoint> {
    return groupBy { it.targetDate to it.timeBand }
        .entries
        .map { (_, records) ->
            val latestRecord = records.maxBy { it.measuredAt }
            ChartWeightPoint(
                measuredAt = latestRecord.measuredAt,
                targetDate = latestRecord.targetDate,
                timeBand = latestRecord.timeBand,
                weightKg = records.sumOf { it.weightKg } / records.size,
                sourceCount = records.size,
            )
        }
        .sortedWith(compareBy<ChartWeightPoint> { it.targetDate }.thenBy { it.timeBand.chartTimeBandOrder() })
}

internal fun calculateMissingWeightPoints(records: List<ChartWeightPoint>): List<ChartWeightPoint> {
    return records.zipWithNext().flatMap { (previous, current) ->
        val previousIndex = previous.chartGroupIndex()
        val currentIndex = current.chartGroupIndex()
        val missingGroupCount = currentIndex - previousIndex - 1
        if (missingGroupCount <= 0) return@flatMap emptyList()

        val weightStep = (current.weightKg - previous.weightKg) / (missingGroupCount + 1)
        (1..missingGroupCount.toInt()).map { groupOffset ->
            val group = chartGroupAt(previousIndex + groupOffset)
            ChartWeightPoint(
                measuredAt = group.targetDate.atTime(group.timeBand.chartRepresentativeTime()),
                targetDate = group.targetDate,
                timeBand = group.timeBand,
                weightKg = previous.weightKg + weightStep * groupOffset,
                sourceCount = 0,
            )
        }
    }
}

internal data class ChartGroup(
    val targetDate: LocalDate,
    val timeBand: String,
)

internal fun ChartWeightPoint.chartGroupIndex(): Long =
    targetDate.toEpochDay() * CHART_TIME_BAND_COUNT + timeBand.chartTimeBandOrder()

internal fun chartGroupAt(index: Long): ChartGroup {
    val targetDate = LocalDate.ofEpochDay(Math.floorDiv(index, CHART_TIME_BAND_COUNT.toLong()))
    val timeBand = when (Math.floorMod(index, CHART_TIME_BAND_COUNT.toLong()).toInt()) {
        CHART_TIME_BAND_MORNING -> "朝"
        else -> "夜"
    }
    return ChartGroup(targetDate, timeBand)
}

internal fun String.chartTimeBandOrder(): Int =
    if (this == "朝") CHART_TIME_BAND_MORNING else CHART_TIME_BAND_NIGHT

internal fun String.chartRepresentativeTime(): LocalTime =
    if (this == "朝") LocalTime.of(8, 0) else LocalTime.of(20, 0)

internal fun LocalDate.shouldShowChartDateNumber(): Boolean {
    if (isMonthEnd()) return true
    val isAroundMonthEnd = minusDays(1).isMonthEnd() || plusDays(1).isMonthEnd()
    return dayOfWeek == DayOfWeek.MONDAY && !isAroundMonthEnd
}

internal fun LocalDate.isMonthEnd(): Boolean =
    plusDays(1).dayOfMonth == 1

internal fun calculateTrendLine(records: List<ChartWeightPoint>): WeightTrendLine? {
    if (records.size <= 1) return null

    val firstAt = records.first().measuredAt
    val count = records.size.toDouble()
    val xValues = records.map { record ->
        Duration.between(firstAt, record.measuredAt).toDaysDouble()
    }
    val sumX = xValues.sum()
    val sumY = records.sumOf { it.weightKg }
    val sumXY = records.foldIndexed(0.0) { index, total, record ->
        total + xValues[index] * record.weightKg
    }
    val sumXX = xValues.sumOf { x ->
        x * x
    }
    val denominator = count * sumXX - sumX * sumX
    if (denominator == 0.0) return null

    val slope = (count * sumXY - sumX * sumY) / denominator
    val intercept = (sumY - slope * sumX) / count
    val lastX = xValues.last()
    return WeightTrendLine(
        startWeightKg = intercept,
        endWeightKg = slope * lastX + intercept,
        slopeKgPerDay = slope,
    )
}

internal fun formatTrendChange(
    trendLine: WeightTrendLine,
    range: WeightChartRange,
    duration: Duration?,
): String {
    val changeKg = trendLine.slopeKgPerDay * (duration?.toDaysDouble() ?: 0.0)
    val sign = if (changeKg > 0.0) "+" else ""
    return "$sign${formatDecimal(changeKg)}kg/${range.label}"
}

internal fun Duration.toDaysDouble(): Double =
    toMillis().toDouble() / MILLIS_PER_DAY

internal fun calculateAverageSteps(
    dailySteps: List<DebugStepDaily>,
    window: ChartTimeWindow,
): Long? {
    val stepsInWindow = dailySteps
        .filter { dailyStep ->
            val targetAt = dailyStep.targetDate.atStartOfDay()
            !targetAt.isBefore(window.startAt) && !targetAt.isAfter(window.endAt)
        }
        .map { it.steps }
    if (stepsInWindow.isEmpty()) return null
    return stepsInWindow.average().roundToLong()
}

internal fun calculateFastingGlucoseChart(
    glucoseRecords: List<DebugGlucoseRecord>,
    window: ChartTimeWindow,
): FastingGlucoseChart? {
    val fastingRecords = glucoseRecords.fastingGlucoseRecords()
    if (fastingRecords.isEmpty()) return null

    val weightedAverage = calculateWeightedAverageFastingGlucose(fastingRecords) ?: return null
    val visibleRecords = fastingRecords
        .filter { record ->
            val pointAt = record.targetDate.atStartOfDay().plusHours(12)
            !pointAt.isBefore(window.startAt) && !pointAt.isAfter(window.endAt)
        }
        .sortedBy { it.measuredAt }

    return FastingGlucoseChart(
        weightedAverageMgDl = weightedAverage,
        visibleRecords = visibleRecords,
    )
}

internal fun calculateWeightedAverageFastingGlucose(
    glucoseRecords: List<DebugGlucoseRecord>,
): Double? {
    val fastingRecords = glucoseRecords.fastingGlucoseRecords()
    if (fastingRecords.isEmpty()) return null

    val weightedRecords = List(GLUCOSE_WEIGHT_COUNT) { index ->
        fastingRecords.getOrElse(index) { fastingRecords.last() }
    }
    return weightedRecords.zip(GLUCOSE_RECENT_WEIGHTS)
        .sumOf { (record, weight) -> record.bloodGlucoseMgDl * weight }
}

internal fun List<DebugGlucoseRecord>.fastingGlucoseRecords(): List<DebugGlucoseRecord> {
    return filter { it.mealRelation.isFastingGlucoseRelation() }
        .sortedByDescending { it.measuredAt }
}

internal fun selectedGraphValuesFor(
    date: LocalDate,
    glucoseRecords: List<DebugGlucoseRecord>,
    a1cDailyRecords: List<DebugA1cDaily>,
    manualRecords: List<ManualHealthRecord>,
    window: ChartTimeWindow,
): SelectedGraphValues {
    val fastingGlucose = calculateFastingGlucoseChart(glucoseRecords, window)
        ?.selectedValueText(date)
    val a1c = a1cDailyRecords
        .maxByOrNull { it.measuredAt }
        ?.let { "${formatDecimal(it.a1cPercent)}%" }
    val bloodPressure = calculateBloodPressureChart(manualRecords, window)
        ?.visibleRecords
        ?.lastOrNull { it.measuredAt.toLocalDate() == date }
        ?.let { "${it.systolic.roundToInt()}/${it.diastolic.roundToInt()}" }
    val waist = calculateWaistChart(manualRecords, window)
        ?.selectedValueText(date)
    return SelectedGraphValues(
        glucoseText = fastingGlucose,
        a1cText = a1c,
        bloodPressureText = bloodPressure,
        waistText = waist,
    )
}

internal fun List<GraphValue>.selectedOrAverageText(date: LocalDate, suffix: String): String? {
    val sortedValues = sortedBy { it.date }
    sortedValues.lastOrNull { it.date == date }?.let { value ->
        return "${formatDecimal(value.value)}$suffix"
    }
    val previous = sortedValues.lastOrNull { it.date.isBefore(date) }
    val next = sortedValues.firstOrNull { it.date.isAfter(date) }
    if (previous == null || next == null) return null
    return "${formatDecimal((previous.value + next.value) / 2.0)}$suffix 平均"
}

internal fun FastingGlucoseChart.selectedValueText(date: LocalDate): String {
    val actualValue = visibleRecords
        .filter { it.targetDate == date }
        .maxByOrNull { it.measuredAt }
        ?.bloodGlucoseMgDl
    return actualValue
        ?.let { "${formatDecimal(it)} mg/dL" }
        ?: "${formatDecimal(weightedAverageMgDl)} mg/dL$ASSUMED_VALUE_SUFFIX"
}

internal fun WaistChart.selectedValueText(date: LocalDate): String? {
    val actualValue = visibleRecords
        .filter { it.measuredAt.toLocalDate() == date }
        .maxByOrNull { it.measuredAt }
        ?.value
    return actualValue
        ?.let { "${formatDecimal(it)} cm" }
        ?: estimatedValueAt(date)?.let { "${formatDecimal(it)} cm$ASSUMED_VALUE_SUFFIX" }
}

internal fun WaistChart.estimatedValueAt(date: LocalDate): Double? {
    val sortedRecords = lineRecords.sortedBy { it.measuredAt }
    if (sortedRecords.isEmpty()) return null
    val targetAt = date.atTime(LocalTime.NOON)
    val previous = sortedRecords.lastOrNull { !it.measuredAt.isAfter(targetAt) }
    val next = sortedRecords.firstOrNull { !it.measuredAt.isBefore(targetAt) }
    if (previous == null) return null
    if (next == null || previous.measuredAt == next.measuredAt) return previous.value

    val totalMillis = Duration.between(previous.measuredAt, next.measuredAt).toMillis()
    if (totalMillis <= 0L) return previous.value
    val elapsedMillis = Duration.between(previous.measuredAt, targetAt).toMillis()
        .coerceIn(0L, totalMillis)
    val ratio = elapsedMillis.toDouble() / totalMillis.toDouble()
    return previous.value + (next.value - previous.value) * ratio
}

internal fun String.isFastingGlucoseRelation(): Boolean =
    this == "空腹時" || this == "食前"

internal fun calculateA1cChart(
    a1cDailyRecords: List<DebugA1cDaily>,
    window: ChartTimeWindow,
): A1cChart? {
    val records = a1cDailyRecords
        .map { record ->
            A1cChartRecord(record.measuredAt, record.a1cPercent)
        }
        .sortedBy { it.measuredAt }
    if (records.isEmpty()) return null
    val visibleRecords = records.filter { record ->
        !record.measuredAt.isBefore(window.startAt) && !record.measuredAt.isAfter(window.endAt)
    }
    val previousRecord = records.lastOrNull { it.measuredAt.isBefore(window.startAt) }
    val lineRecords = (listOfNotNull(previousRecord) + visibleRecords)
        .distinctBy { it.measuredAt to it.value }
    val latestRecord = records.lastOrNull { !it.measuredAt.isAfter(window.endAt) }
    return A1cChart(
        visibleRecords = visibleRecords,
        lineRecords = lineRecords,
        latestRecord = latestRecord,
    )
}

internal fun calculateWaistChart(
    manualRecords: List<ManualHealthRecord>,
    window: ChartTimeWindow,
): WaistChart? {
    val records = manualRecords
        .filter { it.type == ManualRecordType.Waist && it.invalidatedAt == null }
        .mapNotNull { record ->
            record.valueText.removeSuffix(" cm").toDoubleOrNull()?.let { value ->
                WaistChartRecord(record.measuredAt, value)
            }
        }
        .sortedBy { it.measuredAt }
    if (records.isEmpty()) return null
    val visibleRecords = records.filter { record ->
        !record.measuredAt.isBefore(window.startAt) && !record.measuredAt.isAfter(window.endAt)
    }
    val previousRecord = records.lastOrNull { it.measuredAt.isBefore(window.startAt) }
    val lineRecords = (listOfNotNull(previousRecord) + visibleRecords)
        .distinctBy { it.measuredAt to it.value }
    val latestRecord = records.lastOrNull { !it.measuredAt.isAfter(window.endAt) }
    return WaistChart(
        visibleRecords = visibleRecords,
        lineRecords = lineRecords,
        latestRecord = latestRecord,
    )
}

internal fun calculateBloodPressureChart(
    manualRecords: List<ManualHealthRecord>,
    window: ChartTimeWindow,
): BloodPressureChart? {
    val records = manualRecords
        .filter { it.type == ManualRecordType.BloodPressure && it.invalidatedAt == null }
        .groupBy { it.measuredAt.toLocalDate() }
        .mapNotNull { (date, records) ->
            records.averageBloodPressureValue()
                ?.let { average ->
                BloodPressureChartRecord(
                    measuredAt = date.atTime(LocalTime.NOON),
                    systolic = average.systolic,
                    diastolic = average.diastolic,
                )
            }
        }
        .sortedBy { it.measuredAt }
    if (records.isEmpty()) return null
    val visibleRecords = records.filter { record ->
        !record.measuredAt.isBefore(window.startAt) && !record.measuredAt.isAfter(window.endAt)
    }
    val previousRecord = records.lastOrNull { it.measuredAt.isBefore(window.startAt) }
    val lineRecords = (listOfNotNull(previousRecord) + visibleRecords)
        .distinctBy { it.measuredAt to it.systolic to it.diastolic }
    val latestRecord = records.lastOrNull { !it.measuredAt.isAfter(window.endAt) }
    return BloodPressureChart(
        visibleRecords = visibleRecords,
        lineRecords = lineRecords,
        latestRecord = latestRecord,
    )
}

internal fun calculateStepBars(
    dailySteps: List<DebugStepDaily>,
    window: ChartTimeWindow,
): List<StepBar> {
    if (dailySteps.isEmpty()) return emptyList()
    return dailySteps.mapNotNull { dailyStep ->
        if (dailyStep.steps <= 0) return@mapNotNull null
        val centerAt = dailyStep.targetDate.atStartOfDay().plusHours(12)
        if (centerAt.isBefore(window.startAt) || centerAt.isAfter(window.endAt)) return@mapNotNull null
        StepBar(
            centerAt = centerAt,
            steps = dailyStep.steps,
        )
    }
}

internal fun chartEndAtAfterHorizontalDrag(
    currentEndAt: LocalDateTime?,
    earliestEndAt: LocalDateTime?,
    latestEndAt: LocalDateTime?,
    dragAmount: Float,
    width: Float,
): LocalDateTime? {
    if (earliestEndAt == null || latestEndAt == null || width <= 0f) return currentEndAt
    val baseEndAt = currentEndAt ?: latestEndAt
    val totalMillis = Duration.between(earliestEndAt, latestEndAt).toMillis()
    if (totalMillis <= 0L) return baseEndAt.coerceIn(earliestEndAt, latestEndAt)

    val dragMillis = (totalMillis * dragAmount / width).toLong()
    return baseEndAt.minus(Duration.ofMillis(dragMillis)).coerceIn(earliestEndAt, latestEndAt)
}

internal fun nearestChartIndex(
    touchX: Float,
    width: Float,
    records: List<ChartWeightPoint>,
    rangeStartAt: LocalDateTime,
    rangeEndAt: LocalDateTime,
    leftPadding: Float,
    rightPadding: Float,
): Int {
    if (records.size <= 1) return 0
    val chartLeft = leftPadding
    val chartRight = width - rightPadding
    val chartWidth = max(1f, chartRight - chartLeft)
    val clampedX = touchX.coerceIn(chartLeft, chartRight)
    val totalMillis = max(1L, Duration.between(rangeStartAt, rangeEndAt).toMillis())
    var nearestIndex = 0
    var nearestDistance = Float.MAX_VALUE
    records.forEachIndexed { index, record ->
        val elapsedMillis = Duration.between(
            rangeStartAt,
            record.measuredAt.coerceIn(rangeStartAt, rangeEndAt),
        ).toMillis()
        val x = chartLeft + chartWidth * elapsedMillis.toFloat() / totalMillis
        val distance = abs(clampedX - x)
        if (distance < nearestDistance) {
            nearestDistance = distance
            nearestIndex = index
        }
    }
    return nearestIndex
}
