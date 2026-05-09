package com.goenc.healthsheetsync.ui

import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthConnectAvailability
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.PermissionState
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun HealthDebugScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onSaveExternalWorkbook: () -> Unit,
    externalSaveStatus: String?,
    onUploadSpreadsheet: () -> Unit,
    spreadsheetUploadStatus: String?,
    isSpreadsheetUploading: Boolean,
    targetSpreadsheetUrl: String,
    modifier: Modifier = Modifier,
) {
    var showSettings by remember { mutableStateOf(false) }

    if (state.isLoading && state.availability == HealthConnectAvailability.Checking) {
        LoadingScreen(modifier = modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showSettings) {
            SettingsScreen(
                state = state,
                onRequestPermissions = onRequestPermissions,
                onRefresh = onRefresh,
                onSaveExternalWorkbook = onSaveExternalWorkbook,
                externalSaveStatus = externalSaveStatus,
                targetSpreadsheetUrl = targetSpreadsheetUrl,
                onBack = { showSettings = false },
            )
            return@Column
        }

        Header(
            onSettingsClick = { showSettings = true },
            onUploadSpreadsheet = onUploadSpreadsheet,
            canUploadSpreadsheet = !state.isLoading &&
                !isSpreadsheetUploading,
            isUploading = isSpreadsheetUploading,
        )
        spreadsheetUploadStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        DebugSection(title = "体重記録") {
            WeightSummary(state.weightRecords, state.stepDailyRecords)
        }

        DebugSection(title = "血糖値記録") {
            DebugLine("件数", state.glucoseRecords.size.toString())
            state.glucoseRecords.take(10).forEach { record ->
                GlucoseRecordRow(record)
            }
        }
    }
}

@Composable
private fun LoadingScreen(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "データロード中",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun Header(
    onSettingsClick: () -> Unit,
    onUploadSpreadsheet: () -> Unit,
    canUploadSpreadsheet: Boolean,
    isUploading: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "ヘルスシート同期",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            Button(
                onClick = onUploadSpreadsheet,
                enabled = canUploadSpreadsheet,
            ) {
                Text(if (isUploading) "送信中" else "アップロード")
            }
            OutlinedButton(onClick = onSettingsClick) {
                Text("設定")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onSaveExternalWorkbook: () -> Unit,
    externalSaveStatus: String?,
    targetSpreadsheetUrl: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "設定",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        OutlinedButton(onClick = onBack) {
            Text("メインに戻る")
        }
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
        OutlinedButton(onClick = onSaveExternalWorkbook) {
            Text("外部保存")
        }
    }
    externalSaveStatus?.let { status ->
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    DebugSection(title = "アップロード先") {
        DebugLine("対象スプレッドシート", targetSpreadsheetUrl)
        DebugLine("方式", "Googleログインで直接書き込み")
    }
    DebugSection(title = "ヘルスコネクト") {
        DebugLine("利用可否", state.availability.displayText())
        DebugLine("権限", state.permissions.displayText())
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
private fun WeightSummary(
    records: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
) {
    val latestRecord = records.maxByOrNull { it.measuredAt }
    var showWeightList by remember { mutableStateOf(false) }
    var showStepList by remember { mutableStateOf(false) }

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
    LatestStepsLine(dailySteps)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showWeightList = !showWeightList }) {
            Text(if (showWeightList) "体重一覧を閉じる" else "体重一覧")
        }
        OutlinedButton(onClick = { showStepList = !showStepList }) {
            Text(if (showStepList) "歩数一覧を閉じる" else "歩数一覧")
        }
    }
    if (showWeightList) {
        WeightDailySummary(records)
    }
    if (showStepList) {
        StepDailySummary(dailySteps)
    }
    WeightTrendChart(records, dailySteps)
}

@Composable
private fun WeightDailySummary(records: List<DebugWeightRecord>) {
    records
        .groupBy { it.targetDate }
        .toSortedMap(compareByDescending { it })
        .forEach { (date, dailyRecords) ->
            Text(
                text = "$date  朝 ${dailyRecords.weightTextFor("朝")}、夜 ${dailyRecords.weightTextFor("夜")}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
}

@Composable
private fun LatestStepsLine(dailySteps: List<DebugStepDaily>) {
    val steps = dailySteps.maxByOrNull { it.targetDate }
    Text(
        text = steps?.let { "${it.targetDate}  ${it.steps}歩" } ?: "歩数記録はありません",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun StepDailySummary(dailySteps: List<DebugStepDaily>) {
    dailySteps
        .sortedByDescending { it.targetDate }
        .forEach { steps ->
            Text(
                text = "${steps.targetDate}  ${steps.steps}歩",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
}

@Composable
private fun WeightTrendChart(
    records: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
) {
    val sortedRecords = remember(records) { records.sortedBy { it.measuredAt } }
    var selectedRange by remember { mutableStateOf(WeightChartRange.OneMonth) }
    var chartEndAt by remember(sortedRecords, selectedRange) {
        mutableStateOf(sortedRecords.lastOrNull()?.measuredAt)
    }
    val chartWindow = remember(sortedRecords, selectedRange, chartEndAt) {
        selectedRange.window(sortedRecords, chartEndAt)
    }
    val chartRecords = remember(sortedRecords, chartWindow) {
        chartWindow?.let { window ->
            sortedRecords.filter { !it.measuredAt.isBefore(window.startAt) && !it.measuredAt.isAfter(window.endAt) }
        } ?: emptyList()
    }
    val chartPoints = remember(chartRecords) { chartRecords.toChartWeightPoints() }
    var selectedIndex by remember(chartPoints) { mutableStateOf(chartPoints.lastIndex) }
    val selectedPoint = chartPoints.getOrNull(selectedIndex)
    val latestEndAt = sortedRecords.lastOrNull()?.measuredAt
    val earliestEndAt = remember(sortedRecords, selectedRange) {
        selectedRange.minimumEndAt(sortedRecords)
    }
    val sliderPosition = remember(chartEndAt, earliestEndAt, latestEndAt) {
        chartSliderPosition(chartEndAt, earliestEndAt, latestEndAt)
    }
    val currentChartEndAt by rememberUpdatedState(chartEndAt)
    val colorScheme = MaterialTheme.colorScheme
    val lineColor = colorScheme.primary
    val pointColor = colorScheme.primary
    val morningPointColor = Color(0xFF2E7D32)
    val selectedColor = colorScheme.tertiary
    val gridColor = colorScheme.outlineVariant
    val trendLineColor = Color(0xFFD32F2F)
    val stepBarColor = Color(0x667B1FA2)
    val weekBoundaryColor = colorScheme.outlineVariant
    val axisColor = colorScheme.outline

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
        Slider(
            value = sliderPosition,
            onValueChange = { position ->
                chartEndAt = chartEndAtFromSlider(position, earliestEndAt, latestEndAt)
            },
            enabled = earliestEndAt != null && latestEndAt != null && earliestEndAt.isBefore(latestEndAt),
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(earliestEndAt, latestEndAt) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        chartEndAt = chartEndAtAfterHorizontalDrag(
                            currentEndAt = currentChartEndAt,
                            earliestEndAt = earliestEndAt,
                            latestEndAt = latestEndAt,
                            dragAmount = dragAmount,
                            width = size.width.toFloat(),
                        )
                    }
                }
                .pointerInput(chartPoints, chartWindow) {
                    detectTapGestures { offset ->
                        if (chartPoints.isEmpty()) return@detectTapGestures
                        val visibleWindow = chartWindow ?: return@detectTapGestures
                        selectedIndex = nearestChartIndex(
                            touchX = offset.x,
                            width = size.width.toFloat(),
                            records = chartPoints,
                            rangeStartAt = visibleWindow.startAt,
                            rangeEndAt = visibleWindow.endAt,
                            horizontalPadding = CHART_LEFT_PADDING_DP.dp.toPx(),
                        )
                    }
                }
        ) {
            if (chartPoints.isEmpty()) return@Canvas
            val visibleWindow = chartWindow ?: return@Canvas

            val leftPadding = CHART_LEFT_PADDING_DP.dp.toPx()
            val rightPadding = CHART_RIGHT_PADDING_DP.dp.toPx()
            val topPadding = 18.dp.toPx()
            val bottomPadding = 26.dp.toPx()
            val chartLeft = leftPadding
            val chartRight = size.width - rightPadding
            val chartTop = topPadding
            val chartBottom = size.height - bottomPadding
            val chartWidth = max(1f, chartRight - chartLeft)
            val chartHeight = max(1f, chartBottom - chartTop)
            val minWeight = chartPoints.minOf { it.weightKg }
            val maxWeight = chartPoints.maxOf { it.weightKg }
            val weightRange = max(1.0, maxWeight - minWeight)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = axisColor.toArgb()
                textSize = 11.sp.toPx()
            }

            fun xAtTime(measuredAt: LocalDateTime): Float {
                val totalMillis = max(1L, Duration.between(visibleWindow.startAt, visibleWindow.endAt).toMillis())
                val elapsedMillis = Duration.between(
                    visibleWindow.startAt,
                    measuredAt.coerceIn(visibleWindow.startAt, visibleWindow.endAt),
                ).toMillis()
                return chartLeft + chartWidth * elapsedMillis.toFloat() / totalMillis
            }

            fun xAt(index: Int): Float {
                return xAtTime(chartPoints[index].measuredAt)
            }

            fun yAt(weightKg: Double): Float {
                val ratio = ((weightKg - minWeight) / weightRange).toFloat()
                return chartBottom - chartHeight * ratio
            }

            fun stepYAt(steps: Float): Float {
                val ratio = (steps / STEP_CHART_MAX_STEPS).coerceIn(0f, 1f)
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
            drawLine(
                color = axisColor,
                start = Offset(chartLeft, chartTop),
                end = Offset(chartLeft, chartBottom),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = axisColor,
                start = Offset(chartRight, chartTop),
                end = Offset(chartRight, chartBottom),
                strokeWidth = 1.dp.toPx(),
            )
            chartPoints.zipWithNext().forEachIndexed { index, pair ->
                val (previous, current) = pair
                if (previous.targetDate.dayOfWeek == DayOfWeek.SUNDAY &&
                    current.targetDate.dayOfWeek == DayOfWeek.MONDAY
                ) {
                    val x = (xAt(index) + xAt(index + 1)) / 2f
                    drawLine(
                        color = weekBoundaryColor,
                        start = Offset(x, chartTop),
                        end = Offset(x, chartBottom),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }

            drawContext.canvas.nativeCanvas.apply {
                labelPaint.textAlign = Paint.Align.RIGHT
                drawText("1", chartLeft - 6.dp.toPx(), stepYAt(STEP_REFERENCE_STEPS), labelPaint)
                labelPaint.textAlign = Paint.Align.LEFT
                drawText("${formatDecimal(maxWeight)}kg", chartRight + 6.dp.toPx(), chartTop + 4.dp.toPx(), labelPaint)
                drawText("${formatDecimal(minWeight)}kg", chartRight + 6.dp.toPx(), chartBottom, labelPaint)
            }

            calculateStepBars(dailySteps, visibleWindow).forEach { stepBar ->
                val stepRatio = (stepBar.steps.toFloat() / STEP_CHART_MAX_STEPS).coerceIn(0f, 1f)
                val barHeight = chartHeight * stepRatio
                val barWidth = 12.dp.toPx()
                drawRect(
                    color = stepBarColor,
                    topLeft = Offset(xAtTime(stepBar.centerAt) - barWidth / 2f, chartBottom - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                )
            }

            val path = Path()
            chartPoints.forEachIndexed { index, record ->
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

            val missingPointStroke = Stroke(width = 2.dp.toPx())
            calculateMissingWeightPoints(chartPoints).forEach { missingPoint ->
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = Offset(xAtTime(missingPoint.measuredAt), yAt(missingPoint.weightKg)),
                    style = missingPointStroke,
                )
            }

            calculateTrendLine(chartPoints)?.let { trendLine ->
                drawLine(
                    color = trendLineColor,
                    start = Offset(xAt(0), yAt(trendLine.startWeightKg)),
                    end = Offset(xAt(chartPoints.lastIndex), yAt(trendLine.endWeightKg)),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            chartPoints.forEachIndexed { index, record ->
                drawCircle(
                    color = if (record.isMorning()) morningPointColor else pointColor,
                    radius = 3.dp.toPx(),
                    center = Offset(xAt(index), yAt(record.weightKg)),
                )
            }

            chartPoints.getOrNull(selectedIndex)?.let { record ->
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
        selectedPoint?.let { record ->
            val averageLabel = if (record.isAverage) "（平均）" else ""
            Text(
                text = "${record.measuredAt.formatDateTime()}  ${formatDecimal(record.weightKg)} kg$averageLabel",
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
    TwoWeeks("2週間", { latestAt -> latestAt.minusWeeks(2) }, { startAt -> startAt.plusWeeks(2) }),
    OneWeek("1週間", { latestAt -> latestAt.minusWeeks(1) }, { startAt -> startAt.plusWeeks(1) });

    fun window(records: List<DebugWeightRecord>, visibleEndAt: LocalDateTime?): ChartTimeWindow? {
        val rangeEndAt = visibleEndAt ?: records.lastOrNull()?.measuredAt ?: return null
        val rangeStartAt = startAt(rangeEndAt)
        return ChartTimeWindow(rangeStartAt, rangeEndAt)
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

private data class ChartWeightPoint(
    val measuredAt: LocalDateTime,
    val targetDate: LocalDate,
    val timeBand: String,
    val weightKg: Double,
    val sourceCount: Int,
) {
    val isAverage: Boolean
        get() = sourceCount >= 2
}

private data class ChartTimeWindow(
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
)

private data class StepBar(
    val centerAt: LocalDateTime,
    val steps: Long,
)

private fun ChartWeightPoint.isMorning(): Boolean =
    timeBand == "朝"

private fun List<DebugWeightRecord>.weightTextFor(timeBand: String): String {
    return filter { it.timeBand == timeBand }
        .maxByOrNull { it.measuredAt }
        ?.let { "${formatDecimal(it.weightKg)}kg" }
        ?: "-"
}

private fun List<DebugWeightRecord>.toChartWeightPoints(): List<ChartWeightPoint> {
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
        .sortedBy { it.measuredAt }
}

private fun calculateMissingWeightPoints(records: List<ChartWeightPoint>): List<ChartWeightPoint> {
    return records.zipWithNext().flatMap { (previous, current) ->
        val missingDays = ChronoUnit.DAYS.between(previous.targetDate, current.targetDate) - 1
        if (missingDays <= 0) return@flatMap emptyList()

        val weightStep = (current.weightKg - previous.weightKg) / (missingDays + 1)
        (1..missingDays.toInt()).map { dayOffset ->
            val targetDate = previous.targetDate.plusDays(dayOffset.toLong())
            ChartWeightPoint(
                measuredAt = targetDate.atTime(previous.measuredAt.toLocalTime()),
                targetDate = targetDate,
                timeBand = previous.timeBand,
                weightKg = previous.weightKg + weightStep * dayOffset,
                sourceCount = 0,
            )
        }
    }
}

private fun calculateTrendLine(records: List<ChartWeightPoint>): WeightTrendLine? {
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

private fun calculateStepBars(
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

private fun chartSliderPosition(
    currentEndAt: LocalDateTime?,
    earliestEndAt: LocalDateTime?,
    latestEndAt: LocalDateTime?,
): Float {
    if (currentEndAt == null || earliestEndAt == null || latestEndAt == null) return 1f
    val totalMillis = Duration.between(earliestEndAt, latestEndAt).toMillis()
    if (totalMillis <= 0L) return 1f

    val currentMillis = Duration.between(earliestEndAt, currentEndAt.coerceIn(earliestEndAt, latestEndAt)).toMillis()
    return (currentMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
}

private fun chartEndAtFromSlider(
    position: Float,
    earliestEndAt: LocalDateTime?,
    latestEndAt: LocalDateTime?,
): LocalDateTime? {
    if (earliestEndAt == null || latestEndAt == null) return latestEndAt
    val totalMillis = Duration.between(earliestEndAt, latestEndAt).toMillis()
    if (totalMillis <= 0L) return latestEndAt

    return earliestEndAt.plus(Duration.ofMillis((totalMillis * position.coerceIn(0f, 1f)).toLong()))
}

private fun chartEndAtAfterHorizontalDrag(
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

private fun nearestChartIndex(
    touchX: Float,
    width: Float,
    records: List<ChartWeightPoint>,
    rangeStartAt: LocalDateTime,
    rangeEndAt: LocalDateTime,
    horizontalPadding: Float,
): Int {
    if (records.size <= 1) return 0
    val chartLeft = horizontalPadding
    val chartRight = width - horizontalPadding
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

private const val TAG = "HealthSheetSync"
private const val STEP_CHART_MAX_STEPS = 30_000f
private const val STEP_REFERENCE_STEPS = 10_000f
private const val CHART_LEFT_PADDING_DP = 30
private const val CHART_RIGHT_PADDING_DP = 56
