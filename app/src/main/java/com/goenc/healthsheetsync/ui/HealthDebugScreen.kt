package com.goenc.healthsheetsync.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthConnectAvailability
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.PermissionState
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun HealthDebugScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "ヘルスシート同期",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        DebugSection(title = "ヘルスコネクト") {
            DebugLine("利用可否", state.availability.displayText())
            DebugLine("権限", state.permissions.displayText())
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    Log.d(TAG, "Permission request button clicked; invoking onRequestPermissions.")
                    onRequestPermissions()
                },
                enabled = state.canRequestPermissions && !state.isLoading,
            ) {
                Text("権限をリクエスト")
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
            ) {
                Text(if (state.isLoading) "読み込み中" else "データ更新")
            }
        }

        DebugSection(title = "体重記録") {
            WeightSummary(state.weightRecords)
        }

        DebugSection(title = "血糖値記録") {
            DebugLine("件数", state.glucoseRecords.size.toString())
            state.glucoseRecords.take(10).forEach { record ->
                GlucoseRecordRow(record)
            }
        }

        DebugSection(title = "昨日の歩数") {
            val steps = state.yesterdaySteps
            DebugLine("歩数", "${steps?.steps ?: 0}歩")
            DebugLine("集計開始", steps?.aggregationStartAt?.formatDateTime() ?: "不明")
            DebugLine("集計終了", steps?.aggregationEndAt?.formatDateTime() ?: "不明")
        }

        DebugSection(title = "デバッグ") {
            state.sourceSummaries.forEach { source ->
                DebugLine("取得元", source)
            }
            state.debugMessages.forEach { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DebugSection(
    title: String,
    content: @Composable ColumnScopeMarker.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ColumnScopeMarker.content()
        HorizontalDivider()
    }
}

private object ColumnScopeMarker

@Composable
private fun DebugLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WeightSummary(records: List<DebugWeightRecord>) {
    val latestRecord = records.maxByOrNull { it.measuredAt }

    DebugLine("件数", records.size.toString())
    if (latestRecord == null) {
        Text(
            text = "体重記録はありません",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    DebugLine("最新の体重", "${formatDecimal(latestRecord.weightKg)} kg")
    DebugLine("測定日時", "${latestRecord.measuredAt.formatDateTime()} / ${latestRecord.timeBand}")
    WeightTrendChart(records)
}

@Composable
private fun WeightTrendChart(records: List<DebugWeightRecord>) {
    val sortedRecords = remember(records) { records.sortedBy { it.measuredAt } }
    var selectedRange by remember { mutableStateOf(WeightChartRange.OneMonth) }
    var chartEndAt by remember(sortedRecords, selectedRange) {
        mutableStateOf(sortedRecords.lastOrNull()?.measuredAt)
    }
    val chartRecords = remember(sortedRecords, selectedRange, chartEndAt) {
        selectedRange.filter(sortedRecords, chartEndAt)
    }
    var selectedIndex by remember(chartRecords) { mutableStateOf(chartRecords.lastIndex) }
    val selectedRecord = chartRecords.getOrNull(selectedIndex)
    val colorScheme = MaterialTheme.colorScheme
    val lineColor = colorScheme.primary
    val pointColor = colorScheme.primary
    val morningPointColor = Color(0xFF2E7D32)
    val selectedColor = colorScheme.tertiary
    val gridColor = colorScheme.outlineVariant
    val trendLineColor = Color(0xFFD32F2F)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "体重グラフ",
            style = MaterialTheme.typography.labelMedium,
            color = colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeightChartRange.entries.forEach { range ->
                WeightChartRangeButton(
                    range = range,
                    selected = range == selectedRange,
                    onClick = { selectedRange = range },
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(chartRecords) {
                    detectTapGestures { offset ->
                        if (chartRecords.isEmpty()) return@detectTapGestures
                        selectedIndex = nearestChartIndex(
                            touchX = offset.x,
                            width = size.width.toFloat(),
                            pointCount = chartRecords.size,
                            horizontalPadding = 18.dp.toPx(),
                        )
                    }
                }
                .pointerInput(sortedRecords, selectedRange, chartEndAt) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        val currentEndAt = chartEndAt ?: return@detectHorizontalDragGestures
                        chartEndAt = panChartEndAt(
                            currentEndAt = currentEndAt,
                            dragAmount = dragAmount,
                            width = size.width.toFloat(),
                            records = sortedRecords,
                            range = selectedRange,
                        )
                        change.consume()
                    }
                },
        ) {
            if (chartRecords.isEmpty()) return@Canvas

            val leftPadding = 18.dp.toPx()
            val rightPadding = 18.dp.toPx()
            val topPadding = 18.dp.toPx()
            val bottomPadding = 26.dp.toPx()
            val chartLeft = leftPadding
            val chartRight = size.width - rightPadding
            val chartTop = topPadding
            val chartBottom = size.height - bottomPadding
            val chartWidth = max(1f, chartRight - chartLeft)
            val chartHeight = max(1f, chartBottom - chartTop)
            val minWeight = chartRecords.minOf { it.weightKg }
            val maxWeight = chartRecords.maxOf { it.weightKg }
            val weightRange = max(1.0, maxWeight - minWeight)

            fun xAt(index: Int): Float {
                return if (chartRecords.size == 1) {
                    chartLeft + chartWidth / 2f
                } else {
                    chartLeft + chartWidth * index / (chartRecords.lastIndex)
                }
            }

            fun yAt(weightKg: Double): Float {
                val ratio = ((weightKg - minWeight) / weightRange).toFloat()
                return chartBottom - chartHeight * ratio
            }

            repeat(4) { index ->
                val y = chartTop + chartHeight * index / 3f
                drawLine(
                    color = gridColor,
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            val path = Path()
            chartRecords.forEachIndexed { index, record ->
                val point = Offset(xAt(index), yAt(record.weightKg))
                if (index == 0) {
                    path.moveTo(point.x, point.y)
                } else {
                    path.lineTo(point.x, point.y)
                }
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx()),
            )

            calculateTrendLine(chartRecords)?.let { trendLine ->
                drawLine(
                    color = trendLineColor,
                    start = Offset(xAt(0), yAt(trendLine.startWeightKg)),
                    end = Offset(xAt(chartRecords.lastIndex), yAt(trendLine.endWeightKg)),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            chartRecords.forEachIndexed { index, record ->
                drawCircle(
                    color = if (record.isMorning()) morningPointColor else pointColor,
                    radius = 3.dp.toPx(),
                    center = Offset(xAt(index), yAt(record.weightKg)),
                )
            }

            chartRecords.getOrNull(selectedIndex)?.let { record ->
                val selectedPoint = Offset(xAt(selectedIndex), yAt(record.weightKg))
                drawLine(
                    color = selectedColor,
                    start = Offset(selectedPoint.x, chartTop),
                    end = Offset(selectedPoint.x, chartBottom),
                    strokeWidth = 1.dp.toPx(),
                )
                drawCircle(
                    color = if (record.isMorning()) morningPointColor else selectedColor,
                    radius = 5.dp.toPx(),
                    center = selectedPoint,
                )
            }
        }
        selectedRecord?.let { record ->
            Text(
                text = "${record.measuredAt.formatDateTime()}  ${formatDecimal(record.weightKg)} kg",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun WeightChartRangeButton(
    range: WeightChartRange,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(range.label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(range.label)
        }
    }
}

@Composable
private fun GlucoseRecordRow(record: DebugGlucoseRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${record.measuredAt.formatDateTime()} / ${record.timeBand}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("${formatDecimal(record.bloodGlucoseMgDl)} mg/dL / ${record.mealRelation}")
        Text("対象日 ${record.targetDate}")
        Text("識別子 ${record.healthConnectId}")
        Text("取得元 ${record.sourceAppName} / ${record.sourcePackageName}")
        Spacer(Modifier.height(4.dp))
    }
}

private fun HealthConnectAvailability.displayText(): String {
    return when (this) {
        HealthConnectAvailability.Checking -> "確認中"
        HealthConnectAvailability.Available -> "利用可能"
        is HealthConnectAvailability.Unavailable -> reason
    }
}

private fun PermissionState.displayText(): String {
    return when (this) {
        PermissionState.Unknown -> "不明"
        is PermissionState.Granted -> "許可済み ($grantedCount/$requiredCount)"
        is PermissionState.Missing ->
            "不足 ($grantedCount/$requiredCount): ${missingPermissions.joinToString { it.toPermissionLabel() }}"
    }
}

private fun String.toPermissionLabel(): String {
    return when {
        contains("READ_WEIGHT") -> "体重の読み取り"
        contains("READ_BLOOD_GLUCOSE") -> "血糖値の読み取り"
        contains("READ_STEPS") -> "歩数の読み取り"
        else -> this
    }
}

private fun LocalDateTime.formatDateTime(): String =
    format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

private fun formatDecimal(value: Double): String {
    val roundedOneDecimal = (value * 10.0).roundToInt() / 10.0
    return if (roundedOneDecimal % 1.0 == 0.0) {
        roundedOneDecimal.toInt().toString()
    } else {
        roundedOneDecimal.toString()
    }
}

private enum class WeightChartRange(
    val label: String,
    private val startAt: (LocalDateTime) -> LocalDateTime,
    private val endAt: (LocalDateTime) -> LocalDateTime,
) {
    OneMonth("1か月", { latestAt -> latestAt.minusMonths(1) }, { startAt -> startAt.plusMonths(1) }),
    TwoWeeks("2週間", { latestAt -> latestAt.minusWeeks(2) }, { startAt -> startAt.plusWeeks(2) });

    fun filter(records: List<DebugWeightRecord>, visibleEndAt: LocalDateTime?): List<DebugWeightRecord> {
        val rangeEndAt = visibleEndAt ?: records.lastOrNull()?.measuredAt ?: return emptyList()
        val rangeStartAt = startAt(rangeEndAt)
        return records.filter { !it.measuredAt.isBefore(rangeStartAt) && !it.measuredAt.isAfter(rangeEndAt) }
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

private data class WeightTrendLine(
    val startWeightKg: Double,
    val endWeightKg: Double,
)

private fun DebugWeightRecord.isMorning(): Boolean =
    timeBand == "朝"

private fun calculateTrendLine(records: List<DebugWeightRecord>): WeightTrendLine? {
    if (records.size <= 1) return null

    val count = records.size.toDouble()
    val sumX = records.indices.sumOf { it.toDouble() }
    val sumY = records.sumOf { it.weightKg }
    val sumXY = records.foldIndexed(0.0) { index, total, record ->
        total + index * record.weightKg
    }
    val sumXX = records.indices.sumOf { index ->
        val x = index.toDouble()
        x * x
    }
    val denominator = count * sumXX - sumX * sumX
    if (denominator == 0.0) return null

    val slope = (count * sumXY - sumX * sumY) / denominator
    val intercept = (sumY - slope * sumX) / count
    val lastX = records.lastIndex.toDouble()
    return WeightTrendLine(
        startWeightKg = intercept,
        endWeightKg = slope * lastX + intercept,
    )
}

private fun panChartEndAt(
    currentEndAt: LocalDateTime,
    dragAmount: Float,
    width: Float,
    records: List<DebugWeightRecord>,
    range: WeightChartRange,
): LocalDateTime {
    val latestAt = records.lastOrNull()?.measuredAt ?: return currentEndAt
    val minimumEndAt = range.minimumEndAt(records) ?: return currentEndAt
    if (!minimumEndAt.isBefore(latestAt)) return latestAt

    val chartWidth = max(1f, width)
    val visibleDuration = max(1L, range.durationAt(currentEndAt).toMillis())
    val shiftMillis = (-dragAmount / chartWidth * visibleDuration).toLong()
    val shiftedEndAt = currentEndAt.plus(Duration.ofMillis(shiftMillis))
    return shiftedEndAt.coerceIn(minimumEndAt, latestAt)
}

private fun nearestChartIndex(
    touchX: Float,
    width: Float,
    pointCount: Int,
    horizontalPadding: Float,
): Int {
    if (pointCount <= 1) return 0
    val chartLeft = horizontalPadding
    val chartRight = width - horizontalPadding
    val chartWidth = max(1f, chartRight - chartLeft)
    val clampedX = touchX.coerceIn(chartLeft, chartRight)
    var nearestIndex = 0
    var nearestDistance = Float.MAX_VALUE
    repeat(pointCount) { index ->
        val x = chartLeft + chartWidth * index / (pointCount - 1)
        val distance = abs(clampedX - x)
        if (distance < nearestDistance) {
            nearestDistance = distance
            nearestIndex = index
        }
    }
    return nearestIndex
}

private const val TAG = "HealthSheetSync"
