package com.goenc.healthsheetsync.ui

import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

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
    sharedText: String?,
    sharedTextImportStatus: String?,
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
            .background(AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (showSettings) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsScreen(
                    state = state,
                    onRequestPermissions = onRequestPermissions,
                    onRefresh = onRefresh,
                    onSaveExternalWorkbook = onSaveExternalWorkbook,
                    externalSaveStatus = externalSaveStatus,
                    targetSpreadsheetUrl = targetSpreadsheetUrl,
                    onUploadSpreadsheet = onUploadSpreadsheet,
                    spreadsheetUploadStatus = spreadsheetUploadStatus,
                    canUploadSpreadsheet = !state.isLoading && !isSpreadsheetUploading,
                    isSpreadsheetUploading = isSpreadsheetUploading,
                    onBack = { showSettings = false },
                )
            }
            return@Column
        }

        Column(
            modifier = Modifier.padding(start = 22.dp, top = 10.dp, end = 22.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MainHeader(onSettingsClick = { showSettings = true })
                MainSummaryValues(
                    records = state.weightRecords,
                    dailySteps = state.stepDailyRecords,
                    glucoseRecords = state.glucoseRecords,
                )
            }

            WeightTrendChart(state.weightRecords, state.stepDailyRecords, state.glucoseRecords)

            sharedText?.takeIf { it.isNotBlank() }?.let { text ->
                DebugSection(title = "共有テキスト") {
                    sharedTextImportStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppMutedBlue,
                        )
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppText,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainHeader(
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "体重、歩数、血糖",
            style = MaterialTheme.typography.titleMedium,
            color = AppMutedBlue,
        )
        IconButton(onClick = onSettingsClick) {
            SettingsGearIcon()
        }
    }
}

@Composable
private fun MainSummaryValues(
    records: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    glucoseRecords: List<DebugGlucoseRecord>,
) {
    val latestRecord = records.maxByOrNull { it.measuredAt }
    val latestSteps = dailySteps.maxByOrNull { it.targetDate }
    val latestFastingGlucose = glucoseRecords.fastingGlucoseRecords().firstOrNull()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryValue("体重", latestRecord?.let { "${formatDecimal(it.weightKg)}kg" } ?: "-", ChartBlue)
        SummaryValue("歩数", latestSteps?.let { "${it.steps}歩" } ?: "-", ChartStepBar)
        SummaryValue(
            "血糖",
            latestFastingGlucose?.let { "${formatDecimal(it.bloodGlucoseMgDl)}" } ?: "-",
            ChartGlucose,
        )
    }
}

@Composable
private fun SummaryValue(
    label: String,
    value: String,
    color: Color,
) {
    Text(
        text = "$label $value",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        fontSize = 13.sp,
    )
}

@Composable
private fun SettingsGearIcon(
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val strokeWidth = 3.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = size.minDimension * 0.26f
        val toothStartRadius = size.minDimension * 0.36f
        val toothEndRadius = size.minDimension * 0.44f

        repeat(8) { index ->
            val angle = (PI / 4.0 * index).toFloat()
            val start = Offset(
                x = center.x + cos(angle) * toothStartRadius,
                y = center.y + sin(angle) * toothStartRadius,
            )
            val end = Offset(
                x = center.x + cos(angle) * toothEndRadius,
                y = center.y + sin(angle) * toothEndRadius,
            )
            drawLine(
                color = AppText,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        drawCircle(
            color = AppText,
            radius = ringRadius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
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
private fun SettingsScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onSaveExternalWorkbook: () -> Unit,
    externalSaveStatus: String?,
    targetSpreadsheetUrl: String,
    onUploadSpreadsheet: () -> Unit,
    spreadsheetUploadStatus: String?,
    canUploadSpreadsheet: Boolean,
    isSpreadsheetUploading: Boolean,
    onBack: () -> Unit,
) {
    var selectedRecordList by remember { mutableStateOf<RecordListType?>(null) }

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
    DebugSection(title = "操作") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onUploadSpreadsheet,
                enabled = canUploadSpreadsheet,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppPrimary,
                    contentColor = Color.White,
                ),
                modifier = Modifier.defaultMinSize(minWidth = 148.dp, minHeight = 52.dp),
            ) {
                Text(if (isSpreadsheetUploading) "送信中" else "アップロード")
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
                shape = CircleShape,
                modifier = Modifier.defaultMinSize(minWidth = 128.dp, minHeight = 52.dp),
            ) {
                Text(if (state.isLoading) "読み込み中" else "データ更新")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    Log.d(TAG, "Permission request button clicked; invoking onRequestPermissions.")
                    onRequestPermissions()
                },
                enabled = state.canRequestPermissions && !state.isLoading,
                shape = CircleShape,
            ) {
                Text("権限をリクエスト")
            }
            OutlinedButton(
                onClick = onSaveExternalWorkbook,
                shape = CircleShape,
            ) {
                Text("外部保存")
            }
        }
        spreadsheetUploadStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    externalSaveStatus?.let { status ->
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    DebugSection(title = "記録一覧") {
        DebugLine("体重記録の件数", state.weightRecords.size.toString())
        DebugLine("血糖値記録の件数", state.glucoseRecords.size.toString())
        DebugLine("歩数記録の日数", state.stepDailyRecords.size.toString())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordListButton(
                label = "体重",
                selected = selectedRecordList == RecordListType.Weight,
                onClick = { selectedRecordList = RecordListType.Weight },
            )
            RecordListButton(
                label = "血糖値",
                selected = selectedRecordList == RecordListType.Glucose,
                onClick = { selectedRecordList = RecordListType.Glucose },
            )
            RecordListButton(
                label = "歩数",
                selected = selectedRecordList == RecordListType.Steps,
                onClick = { selectedRecordList = RecordListType.Steps },
            )
        }
        when (selectedRecordList) {
            RecordListType.Weight -> WeightDailySummary(state.weightRecords)
            RecordListType.Glucose -> GlucoseRecordSummary(state.glucoseRecords)
            RecordListType.Steps -> StepDailySummary(state.stepDailyRecords)
            null -> {
                Text(
                    text = "表示する記録を選択してください",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppText,
                )
            }
        }
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = AppText,
        )
        ColumnScopeMarker.content()
        HorizontalDivider(color = DividerColor)
    }
}

private object ColumnScopeMarker

private enum class RecordListType {
    Weight,
    Glucose,
    Steps,
}

@Composable
private fun RecordListButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppPrimary,
                contentColor = Color.White,
            ),
            modifier = Modifier.defaultMinSize(minWidth = 92.dp, minHeight = 44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText),
            modifier = Modifier.defaultMinSize(minWidth = 92.dp, minHeight = 44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(label)
        }
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AppMutedBlue,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = AppText,
        )
    }
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
private fun GlucoseRecordSummary(records: List<DebugGlucoseRecord>) {
    if (records.isEmpty()) {
        Text(
            text = "血糖値記録はありません",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    records
        .sortedByDescending { it.measuredAt }
        .forEach { record ->
            Text(
                text = "${record.measuredAt.formatDateTime()}  ${formatDecimal(record.bloodGlucoseMgDl)} mg/dL  ${record.mealRelation}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
}

@Composable
private fun WeightTrendChart(
    records: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    glucoseRecords: List<DebugGlucoseRecord>,
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
    var selectedDate by remember(chartPoints) { mutableStateOf<LocalDate?>(null) }
    val selectedDay = remember(chartPoints, dailySteps, selectedDate) {
        selectedDate?.let { date ->
            val dayPoints = chartPoints.filter { it.targetDate == date }
            if (dayPoints.isEmpty()) {
                null
            } else {
                ChartDaySelection(
                    date = date,
                    morning = dayPoints.firstOrNull { it.timeBand == "朝" },
                    night = dayPoints.firstOrNull { it.timeBand == "夜" },
                    steps = dailySteps.firstOrNull { it.targetDate == date },
                )
            }
        }
    }
    val latestEndAt = sortedRecords.lastOrNull()?.measuredAt
    val earliestEndAt = remember(sortedRecords, selectedRange) {
        selectedRange.minimumEndAt(sortedRecords)
    }
    val currentChartEndAt by rememberUpdatedState(chartEndAt)
    val lineColor = ChartBlue
    val selectedColor = AppPrimary
    val gridColor = ChartGrid
    val trendLineColor = ChartTrend
    val stepBarColor = ChartStepBar
    val weekBoundaryColor = ChartGrid
    val axisColor = ChartLabel
    val missingPointColor = ChartMissingPoint

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
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
                            selectedDate = nearestChartIndex(
                                touchX = offset.x,
                                width = size.width.toFloat(),
                                records = chartPoints,
                                rangeStartAt = visibleWindow.startAt,
                                rangeEndAt = visibleWindow.endAt,
                                leftPadding = CHART_LEFT_PADDING_DP.dp.toPx(),
                                rightPadding = CHART_RIGHT_PADDING_DP.dp.toPx(),
                            ).let { chartPoints.getOrNull(it)?.targetDate }
                        }
                    }
            ) {
                if (chartPoints.isEmpty()) return@Canvas
                val visibleWindow = chartWindow ?: return@Canvas

                val leftPadding = CHART_LEFT_PADDING_DP.dp.toPx()
                val rightPadding = CHART_RIGHT_PADDING_DP.dp.toPx()
                val topPadding = 28.dp.toPx()
                val bottomPadding = 28.dp.toPx()
                val chartLeft = leftPadding
                val chartRight = size.width - rightPadding
                val chartTop = topPadding
                val chartBottom = size.height - bottomPadding
                val chartWidth = max(1f, chartRight - chartLeft)
                val chartHeight = max(1f, chartBottom - chartTop)
                val minWeight = floor(chartPoints.minOf { it.weightKg } - CHART_WEIGHT_LOWER_PADDING_KG)
                val maxWeight = ceil(chartPoints.maxOf { it.weightKg } + CHART_WEIGHT_UPPER_PADDING_KG)
                val weightRange = max(1.0, maxWeight - minWeight)
                val trendLine = calculateTrendLine(chartPoints)
                val averageSteps = calculateAverageSteps(dailySteps, visibleWindow)
                val glucoseChart = calculateFastingGlucoseChart(glucoseRecords, visibleWindow)
                val solidWeightLines = generateSequence(maxWeight) { it - 1.0 }
                    .takeWhile { it >= minWeight }
                    .toList()
                val halfWeightLines = generateSequence(maxWeight - 0.5) { it - 1.0 }
                    .takeWhile { it > minWeight }
                    .toList()
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = axisColor.toArgb()
                    textSize = 12.sp.toPx()
                }
                val monthLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = axisColor.toArgb()
                    textSize = 9.sp.toPx()
                }
                val trendSummaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ChartSummary.toArgb()
                    textSize = 13.sp.toPx()
                }
                val glucosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ChartGlucose.toArgb()
                    textSize = 12.sp.toPx()
                }
                val dashedGrid = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))

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

            halfWeightLines.forEach { weightKg ->
                val y = yAt(weightKg)
                drawLine(
                    color = gridColor,
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashedGrid,
                )
            }
            solidWeightLines.forEach { weightKg ->
                val y = yAt(weightKg)
                drawLine(
                    color = gridColor,
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
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
                solidWeightLines.forEach { weightKg ->
                    val y = yAt(weightKg)
                    drawText("${formatDecimal(weightKg)}kg", chartLeft - 8.dp.toPx(), y + 4.dp.toPx(), labelPaint)
                }
                labelPaint.textAlign = Paint.Align.LEFT
                drawText("1", chartRight + 8.dp.toPx(), stepYAt(STEP_REFERENCE_STEPS), labelPaint)
                labelPaint.textAlign = Paint.Align.CENTER
                monthLabelPaint.textAlign = Paint.Align.LEFT
                val visibleStartDate = visibleWindow.startAt.toLocalDate()
                val visibleStartMonthText = "${visibleStartDate.monthValue}月"
                drawText(
                    visibleStartMonthText,
                    chartLeft,
                    chartBottom + 11.dp.toPx(),
                    monthLabelPaint,
                )
                val monthLabelMinX = chartLeft + monthLabelPaint.measureText(visibleStartMonthText) + 8.dp.toPx()
                val visibleDates = generateSequence(visibleWindow.startAt.toLocalDate()) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(visibleWindow.endAt.toLocalDate()) }
                    .toList()
                val dateLabelPositions = visibleDates
                    .filter { it.shouldShowChartDateNumber() }
                    .map { date ->
                        val x = xAtTime(date.atStartOfDay().plusHours(12))
                            .coerceIn(chartLeft + 6.dp.toPx(), chartRight - 6.dp.toPx())
                        val text = date.dayOfMonth.toString()
                        ChartDateLabel(date, x, labelPaint.measureText(text))
                    }
                val hiddenDateLabels = dateLabelPositions
                    .zipWithNext()
                    .mapNotNull { (previous, current) ->
                        if (previous.right + 4.dp.toPx() > current.left) previous.date else null
                    }
                    .toSet()
                visibleDates.forEach { date ->
                        val x = xAtTime(date.atStartOfDay().plusHours(12))
                            .coerceIn(chartLeft + 6.dp.toPx(), chartRight - 6.dp.toPx())
                        if (date.dayOfMonth == 1 && date != visibleStartDate && x >= monthLabelMinX) {
                            drawText(
                                "${date.monthValue}月",
                                x,
                                chartBottom + 11.dp.toPx(),
                                monthLabelPaint,
                            )
                        }
                        if (date.shouldShowChartDateNumber() && date !in hiddenDateLabels) {
                            drawText(
                                date.dayOfMonth.toString(),
                                x,
                                chartBottom + 22.dp.toPx(),
                                labelPaint,
                            )
                        } else {
                            drawCircle(
                                x,
                                chartBottom + 17.dp.toPx(),
                                1.5.dp.toPx(),
                                labelPaint,
                            )
                        }
                }
                trendLine?.let {
                    trendSummaryPaint.textAlign = Paint.Align.LEFT
                    drawText(
                        formatTrendChange(it, selectedRange),
                        chartLeft + 8.dp.toPx(),
                        chartTop + 16.dp.toPx(),
                        trendSummaryPaint,
                    )
                }
                drawText(
                    "平均歩数 ${averageSteps?.let { "%,d歩".format(it) } ?: "-"}",
                    chartLeft + 8.dp.toPx(),
                    chartTop + 34.dp.toPx(),
                    trendSummaryPaint,
                )
                drawText(
                    "平均血糖 ${glucoseChart?.let { "${formatDecimal(it.weightedAverageMgDl)} mg/dL" } ?: "-"}",
                    chartLeft + 8.dp.toPx(),
                    chartTop + 52.dp.toPx(),
                    trendSummaryPaint,
                )
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

            glucoseChart?.let { chart ->
                val glucoseY = chartBottom - 54.dp.toPx()
                drawLine(
                    color = ChartGlucose,
                    start = Offset(chartLeft, glucoseY),
                    end = Offset(chartRight, glucoseY),
                    strokeWidth = 1.5.dp.toPx(),
                )
                chart.visibleRecords.forEach { record ->
                    drawCircle(
                        color = ChartGlucose,
                        radius = 4.dp.toPx(),
                        center = Offset(xAtTime(record.targetDate.atStartOfDay().plusHours(12)), glucoseY),
                    )
                }
                drawContext.canvas.nativeCanvas.apply {
                    glucosePaint.textAlign = Paint.Align.RIGHT
                    drawText(
                        "${formatDecimal(chart.weightedAverageMgDl)}",
                        chartRight - 4.dp.toPx(),
                        glucoseY - 4.dp.toPx(),
                        glucosePaint,
                    )
                }
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
                    color = missingPointColor,
                    radius = 4.dp.toPx(),
                    center = Offset(xAtTime(missingPoint.measuredAt), yAt(missingPoint.weightKg)),
                    style = missingPointStroke,
                )
            }

            trendLine?.let {
                drawLine(
                    color = trendLineColor,
                    start = Offset(xAt(0), yAt(it.startWeightKg)),
                    end = Offset(xAt(chartPoints.lastIndex), yAt(it.endWeightKg)),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            chartPoints.forEachIndexed { index, record ->
                val center = Offset(xAt(index), yAt(record.weightKg))
                if (record.isMorning()) {
                    drawCircle(
                        color = lineColor,
                        radius = 4.dp.toPx(),
                        center = center,
                    )
                } else {
                    drawCircle(
                        color = AppBackground,
                        radius = 4.dp.toPx(),
                        center = center,
                    )
                    drawCircle(
                        color = lineColor,
                        radius = 4.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

                selectedDay?.points?.forEach { record ->
                    chartPoints.indexOf(record).takeIf { it >= 0 }?.let { index ->
                        val selectedPoint = Offset(xAt(index), yAt(record.weightKg))
                        drawLine(
                            color = selectedColor,
                            start = Offset(selectedPoint.x, chartTop),
                            end = Offset(selectedPoint.x, chartBottom),
                            strokeWidth = 1.dp.toPx(),
                        )
                        drawCircle(
                            color = selectedColor,
                            radius = 6.dp.toPx(),
                            center = selectedPoint,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            selectedDay?.let { day ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 42.dp, end = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = PopupBackground,
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "${day.date.monthValue}月${day.date.dayOfMonth}日",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppText,
                        )
                        Text(
                            text = "朝 ${day.morning.weightText()}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ChartBlue,
                        )
                        Text(
                            text = "夜 ${day.night.weightText()}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ChartBlue,
                        )
                        Text(
                            text = "差 ${day.weightDifferenceText()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppText,
                        )
                        Text(
                            text = "歩数 ${day.steps?.steps?.let { "${it}歩" } ?: "-"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppText,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WeightChartRange.entries.forEach { range ->
                WeightChartRangeButton(
                    range = range,
                    selected = range == selectedRange,
                    onClick = { selectedRange = range },
                )
            }
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
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppPrimary,
                contentColor = Color.White,
            ),
            modifier = Modifier.defaultMinSize(minWidth = 72.dp, minHeight = 40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(range.label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText),
            modifier = Modifier.defaultMinSize(minWidth = 72.dp, minHeight = 40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
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

private data class ChartDaySelection(
    val date: LocalDate,
    val morning: ChartWeightPoint?,
    val night: ChartWeightPoint?,
    val steps: DebugStepDaily?,
) {
    val points: List<ChartWeightPoint>
        get() = listOfNotNull(morning, night)
}

private data class ChartTimeWindow(
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
)

private data class StepBar(
    val centerAt: LocalDateTime,
    val steps: Long,
)

private data class FastingGlucoseChart(
    val weightedAverageMgDl: Double,
    val visibleRecords: List<DebugGlucoseRecord>,
)

private data class ChartDateLabel(
    val date: LocalDate,
    val x: Float,
    val width: Float,
) {
    val left: Float
        get() = x - width / 2f
    val right: Float
        get() = x + width / 2f
}

private fun ChartWeightPoint.isMorning(): Boolean =
    timeBand == "朝"

private fun ChartWeightPoint?.weightText(): String =
    this?.let { "${formatDecimal(it.weightKg)} kg" } ?: "-"

private fun ChartDaySelection.weightDifferenceText(): String {
    val morningWeight = morning?.weightKg ?: return "-"
    val nightWeight = night?.weightKg ?: return "-"
    return "${formatDecimal(abs(nightWeight - morningWeight))} kg"
}

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
        .sortedWith(compareBy<ChartWeightPoint> { it.targetDate }.thenBy { it.timeBand.chartTimeBandOrder() })
}

private fun calculateMissingWeightPoints(records: List<ChartWeightPoint>): List<ChartWeightPoint> {
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

private data class ChartGroup(
    val targetDate: LocalDate,
    val timeBand: String,
)

private fun ChartWeightPoint.chartGroupIndex(): Long =
    targetDate.toEpochDay() * CHART_TIME_BAND_COUNT + timeBand.chartTimeBandOrder()

private fun chartGroupAt(index: Long): ChartGroup {
    val targetDate = LocalDate.ofEpochDay(Math.floorDiv(index, CHART_TIME_BAND_COUNT.toLong()))
    val timeBand = when (Math.floorMod(index, CHART_TIME_BAND_COUNT.toLong()).toInt()) {
        CHART_TIME_BAND_MORNING -> "朝"
        else -> "夜"
    }
    return ChartGroup(targetDate, timeBand)
}

private fun String.chartTimeBandOrder(): Int =
    if (this == "朝") CHART_TIME_BAND_MORNING else CHART_TIME_BAND_NIGHT

private fun String.chartRepresentativeTime(): LocalTime =
    if (this == "朝") LocalTime.of(8, 0) else LocalTime.of(20, 0)

private fun LocalDate.shouldShowChartDateNumber(): Boolean {
    if (isMonthEnd()) return true
    val isAroundMonthEnd = minusDays(1).isMonthEnd() || plusDays(1).isMonthEnd()
    return dayOfWeek == DayOfWeek.MONDAY && !isAroundMonthEnd
}

private fun LocalDate.isMonthEnd(): Boolean =
    plusDays(1).dayOfMonth == 1

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

private fun formatTrendChange(
    trendLine: WeightTrendLine,
    range: WeightChartRange,
): String {
    val changeKg = trendLine.endWeightKg - trendLine.startWeightKg
    val sign = if (changeKg > 0.0) "+" else ""
    return "$sign${formatDecimal(changeKg)}kg/${range.label}"
}

private fun calculateAverageSteps(
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

private fun calculateFastingGlucoseChart(
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

private fun calculateWeightedAverageFastingGlucose(
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

private fun List<DebugGlucoseRecord>.fastingGlucoseRecords(): List<DebugGlucoseRecord> {
    return filter { it.mealRelation.isFastingGlucoseRelation() }
        .sortedByDescending { it.measuredAt }
}

private fun String.isFastingGlucoseRelation(): Boolean =
    this == "空腹時" || this == "食前"

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

private const val TAG = "HealthSheetSync"
private const val STEP_CHART_MAX_STEPS = 30_000f
private const val STEP_REFERENCE_STEPS = 10_000f
private const val CHART_LEFT_PADDING_DP = 44
private const val CHART_RIGHT_PADDING_DP = 8
private const val CHART_TIME_BAND_MORNING = 0
private const val CHART_TIME_BAND_NIGHT = 1
private const val CHART_TIME_BAND_COUNT = 2
private const val CHART_WEIGHT_LOWER_PADDING_KG = 1.0
private const val CHART_WEIGHT_UPPER_PADDING_KG = 1.5
private const val CHART_EMPTY_EDGE_PADDING_DAYS = 2L
private const val GLUCOSE_WEIGHT_COUNT = 3
private val GLUCOSE_RECENT_WEIGHTS = listOf(0.5, 0.3, 0.2)
private val AppBackground = Color(0xFFFAFAFC)
private val HeaderBackground = Color(0xFFF2F2F3)
private val AppText = Color(0xFF202128)
private val AppMutedBlue = Color(0xFF4C6399)
private val AppPrimary = Color(0xFF4B629B)
private val DividerColor = Color(0xFFE5E5EA)
private val ChartBlue = Color(0xFF07577D)
private val ChartGrid = Color(0xFFE4E4E4)
private val ChartTrend = Color(0xFF8F8F8F)
private val ChartSummary = Color(0xFF043C5A)
private val ChartStepBar = Color(0x337E57B2)
private val ChartGlucose = Color(0xFFC33A2B)
private val ChartLabel = Color(0xFF7D7D84)
private val ChartMissingPoint = Color(0xFFB0B0B0)
private val PopupBackground = Color(0xF7FFFFFF)
