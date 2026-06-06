package com.goenc.healthsheetsync.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun WeightTrendChart(
    records: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    glucoseRecords: List<DebugGlucoseRecord>,
    a1cDailyRecords: List<DebugA1cDaily>,
    manualRecords: List<ManualHealthRecord>,
    onAddManualRecord: () -> Unit,
) {
    val sortedRecords = remember(records) { records.sortedBy { it.measuredAt } }
    var selectedRange by remember { mutableStateOf(WeightChartRange.TwoWeeks) }
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
    val selectedGraphValues = remember(selectedDate, chartWindow, glucoseRecords, a1cDailyRecords, manualRecords) {
        chartWindow?.let { window ->
            selectedDate?.let { date ->
                selectedGraphValuesFor(
                    date = date,
                    glucoseRecords = glucoseRecords,
                    a1cDailyRecords = a1cDailyRecords,
                    manualRecords = manualRecords,
                    window = window,
                )
            }
        }
    }
    val latestEndAt = sortedRecords.lastOrNull()?.measuredAt
    val trendDuration = if (selectedRange == WeightChartRange.SixMonths) {
        chartRecords.firstOrNull()?.measuredAt?.let { firstAt ->
            chartRecords.lastOrNull()?.measuredAt?.let { lastAt ->
                Duration.between(firstAt, lastAt)
            }
        }
    } else {
        (chartEndAt ?: latestEndAt)?.let { selectedRange.durationAt(it) }
    }
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
                .height(465.dp),
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
                val a1cChart = calculateA1cChart(a1cDailyRecords, visibleWindow)
                val waistChart = calculateWaistChart(manualRecords, visibleWindow)
                val bloodPressureChart = calculateBloodPressureChart(manualRecords, visibleWindow)
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
                val weightLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = lineColor.toArgb()
                    textSize = 12.sp.toPx()
                }
                val stepLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ChartStepText.toArgb()
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
                val a1cPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ChartA1c.toArgb()
                    textSize = 12.sp.toPx()
                }
                val waistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ChartWaist.toArgb()
                    textSize = 12.sp.toPx()
                }
                val bloodPressurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ChartBloodPressureSystolic.toArgb()
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

            fun a1cYAt(value: Double): Float {
                val ratio = ((value - A1C_CHART_MIN) / (A1C_CHART_MAX - A1C_CHART_MIN)).toFloat()
                    .coerceIn(0f, 1f)
                return chartBottom - chartHeight * ratio
            }

            fun waistYAt(value: Double): Float {
                val bandBottomRatio =
                    ((WAIST_CHART_DISPLAY_BAND_MIN_CM - WAIST_CHART_MIN_CM) /
                        (WAIST_CHART_MAX_CM - WAIST_CHART_MIN_CM)).toFloat().coerceIn(0f, 1f)
                val bandTopRatio =
                    ((WAIST_CHART_DISPLAY_BAND_MAX_CM - WAIST_CHART_MIN_CM) /
                        (WAIST_CHART_MAX_CM - WAIST_CHART_MIN_CM)).toFloat().coerceIn(0f, 1f)
                val bandBottom = chartBottom - chartHeight * bandBottomRatio
                val bandTop = chartBottom - chartHeight * bandTopRatio
                val bandHeight = max(1f, bandBottom - bandTop)
                val ratio = ((value - WAIST_CHART_DISPLAY_MIN_CM) /
                    (WAIST_CHART_DISPLAY_MAX_CM - WAIST_CHART_DISPLAY_MIN_CM)).toFloat()
                    .coerceIn(0f, 1f)
                return bandBottom - bandHeight * ratio
            }

            val desiredStepReferenceY = (
                waistYAt(WAIST_CHART_DISPLAY_MAX_CM) + STEP_REFERENCE_CLEARANCE_DP.dp.toPx()
            ).coerceAtMost(chartBottom - 20.dp.toPx())
            val stepReferenceRatio = ((chartBottom - desiredStepReferenceY) / chartHeight)
                .coerceIn(0.05f, 0.95f)
            val stepChartMaxSteps = max(STEP_REFERENCE_STEPS, STEP_REFERENCE_STEPS / stepReferenceRatio)

            fun stepYAt(steps: Float): Float {
                val ratio = (steps / stepChartMaxSteps).coerceIn(0f, 1f)
                return chartBottom - chartHeight * ratio
            }

            fun bloodPressureYAt(value: Double): Float {
                val bloodPressureBottom = chartTop + chartHeight * BLOOD_PRESSURE_CHART_HEIGHT_RATIO
                val bloodPressureHeight = max(1f, bloodPressureBottom - chartTop)
                val ratio = ((value - BLOOD_PRESSURE_CHART_MIN) /
                    (BLOOD_PRESSURE_CHART_MAX - BLOOD_PRESSURE_CHART_MIN)).toFloat()
                    .coerceIn(0f, 1f)
                return bloodPressureBottom - bloodPressureHeight * ratio
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
            WAIST_CHART_LINES_CM.forEach { waistCm ->
                val y = waistYAt(waistCm)
                drawLine(
                    color = ChartWaist.copy(alpha = 0.28f),
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashedGrid,
                )
            }
            BLOOD_PRESSURE_CHART_LINES.forEach { bloodPressure ->
                val y = bloodPressureYAt(bloodPressure)
                drawLine(
                    color = ChartBloodPressureSystolic.copy(alpha = 0.22f),
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashedGrid,
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
                weightLabelPaint.textAlign = Paint.Align.RIGHT
                solidWeightLines.forEach { weightKg ->
                    val y = yAt(weightKg)
                    drawText("${formatDecimal(weightKg)}kg", chartLeft - 8.dp.toPx(), y + 4.dp.toPx(), weightLabelPaint)
                }
                stepLabelPaint.textAlign = Paint.Align.LEFT
                val stepLabelBaseline =
                    stepYAt(STEP_REFERENCE_STEPS) - (stepLabelPaint.fontMetrics.ascent + stepLabelPaint.fontMetrics.descent) / 2f
                drawText("1万", chartRight + 8.dp.toPx(), stepLabelBaseline, stepLabelPaint)
                waistPaint.textAlign = Paint.Align.LEFT
                WAIST_CHART_LINES_CM.forEach { waistCm ->
                    drawText("${waistCm.toInt()}", chartRight + 8.dp.toPx(), waistYAt(waistCm) + 4.dp.toPx(), waistPaint)
                }
                bloodPressurePaint.textAlign = Paint.Align.LEFT
                BLOOD_PRESSURE_CHART_LINES.forEach { bloodPressure ->
                    drawText(
                        bloodPressure.toInt().toString(),
                        chartRight + 8.dp.toPx(),
                        bloodPressureYAt(bloodPressure) + 4.dp.toPx(),
                        bloodPressurePaint,
                    )
                }
                labelPaint.textAlign = Paint.Align.CENTER
                monthLabelPaint.textAlign = Paint.Align.LEFT
                val visibleStartDate = visibleWindow.startAt.toLocalDate()
                val visibleStartMonthText = "${visibleStartDate.monthValue}月"
                val isDotOnlyRange = selectedRange == WeightChartRange.SixMonths
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
                        if (!isDotOnlyRange && date.shouldShowChartDateNumber() && date !in hiddenDateLabels) {
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
                                if (isDotOnlyRange) 0.8.dp.toPx() else 1.dp.toPx(),
                                labelPaint,
                            )
                        }
                }
                trendLine?.let {
                    trendSummaryPaint.textAlign = Paint.Align.LEFT
                    drawText(
                        formatTrendChange(it, selectedRange, trendDuration),
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
                val stepRatio = (stepBar.steps.toFloat() / stepChartMaxSteps).coerceIn(0f, 1f)
                val barHeight = chartHeight * stepRatio
                val barWidth = 12.dp.toPx()
                drawRect(
                    color = stepBarColor,
                    topLeft = Offset(xAtTime(stepBar.centerAt) - barWidth / 2f, chartBottom - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                )
            }
            val stepReferenceY = stepYAt(STEP_REFERENCE_STEPS)
            drawLine(
                color = ChartStepText.copy(alpha = 0.6f),
                start = Offset(chartLeft, stepReferenceY),
                end = Offset(chartRight, stepReferenceY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = dashedGrid,
            )

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

            a1cChart?.let { chart ->
                val linePoints = chart.lineRecords
                if (linePoints.isNotEmpty()) {
                    val a1cPath = Path()
                    linePoints.forEachIndexed { index, record ->
                        val point = Offset(xAtTime(record.measuredAt), a1cYAt(record.value))
                        if (index == 0) {
                            a1cPath.moveTo(point.x, point.y)
                        } else {
                            a1cPath.lineTo(point.x, point.y)
                        }
                    }
                    drawPath(
                        path = a1cPath,
                        color = ChartA1c,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                if (chart.visibleRecords.isNotEmpty()) {
                    chart.visibleRecords.forEach { record ->
                        drawCircle(
                            color = ChartA1c,
                            radius = 4.dp.toPx(),
                            center = Offset(xAtTime(record.measuredAt), a1cYAt(record.value)),
                        )
                    }
                }
                chart.latestRecord?.let { latest ->
                    val latestX = xAtTime(latest.measuredAt)
                    val latestY = a1cYAt(latest.value)
                    drawLine(
                        color = ChartA1c,
                        start = Offset(latestX, latestY),
                        end = Offset(chartRight, latestY),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawContext.canvas.nativeCanvas.apply {
                        a1cPaint.textAlign = Paint.Align.RIGHT
                        drawText(
                            formatDecimal(latest.value),
                            chartRight - 4.dp.toPx(),
                            latestY - 4.dp.toPx(),
                            a1cPaint,
                        )
                    }
                }
            }

            waistChart?.let { chart ->
                val linePoints = chart.lineRecords
                if (linePoints.isNotEmpty()) {
                    val waistPath = Path()
                    linePoints.forEachIndexed { index, record ->
                        val point = Offset(xAtTime(record.measuredAt), waistYAt(record.value))
                        if (index == 0) {
                            waistPath.moveTo(point.x, point.y)
                        } else {
                            waistPath.lineTo(point.x, point.y)
                        }
                    }
                    drawPath(
                        path = waistPath,
                        color = ChartWaist,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                chart.visibleRecords.forEach { record ->
                    drawCircle(
                        color = ChartWaist,
                        radius = 4.dp.toPx(),
                        center = Offset(xAtTime(record.measuredAt), waistYAt(record.value)),
                    )
                }
                chart.latestRecord?.let { latest ->
                    val latestX = xAtTime(latest.measuredAt)
                    val latestY = waistYAt(latest.value)
                    drawLine(
                        color = ChartWaist,
                        start = Offset(latestX, latestY),
                        end = Offset(chartRight, latestY),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawContext.canvas.nativeCanvas.apply {
                        waistPaint.textAlign = Paint.Align.RIGHT
                        drawText(
                            formatDecimal(latest.value),
                            chartRight - 4.dp.toPx(),
                            latestY - 4.dp.toPx(),
                            waistPaint,
                        )
                    }
                }
            }

            bloodPressureChart?.let { chart ->
                val linePoints = chart.lineRecords
                if (linePoints.isNotEmpty()) {
                    val areaPath = Path()
                    linePoints.forEachIndexed { index, record ->
                        val point = Offset(xAtTime(record.measuredAt), bloodPressureYAt(record.systolic))
                        if (index == 0) {
                            areaPath.moveTo(point.x, point.y)
                        } else {
                            areaPath.lineTo(point.x, point.y)
                        }
                    }
                    linePoints.asReversed().forEach { record ->
                        areaPath.lineTo(
                            xAtTime(record.measuredAt),
                            bloodPressureYAt(record.diastolic),
                        )
                    }
                    areaPath.close()
                    drawPath(
                        path = areaPath,
                        color = ChartBloodPressureArea,
                    )

                    val systolicPath = Path()
                    val diastolicPath = Path()
                    linePoints.forEachIndexed { index, record ->
                        val x = xAtTime(record.measuredAt)
                        val systolicY = bloodPressureYAt(record.systolic)
                        val diastolicY = bloodPressureYAt(record.diastolic)
                        if (index == 0) {
                            systolicPath.moveTo(x, systolicY)
                            diastolicPath.moveTo(x, diastolicY)
                        } else {
                            systolicPath.lineTo(x, systolicY)
                            diastolicPath.lineTo(x, diastolicY)
                        }
                    }
                    drawPath(
                        path = systolicPath,
                        color = ChartBloodPressureSystolic,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawPath(
                        path = diastolicPath,
                        color = ChartBloodPressureDiastolic,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                chart.visibleRecords.forEach { record ->
                    val x = xAtTime(record.measuredAt)
                    drawCircle(
                        color = ChartBloodPressureSystolic,
                        radius = 3.5.dp.toPx(),
                        center = Offset(x, bloodPressureYAt(record.systolic)),
                    )
                    drawCircle(
                        color = ChartBloodPressureDiastolic,
                        radius = 3.5.dp.toPx(),
                        center = Offset(x, bloodPressureYAt(record.diastolic)),
                    )
                }
                chart.latestRecord?.let { latest ->
                    drawContext.canvas.nativeCanvas.apply {
                        bloodPressurePaint.textAlign = Paint.Align.RIGHT
                        drawText(
                            "${latest.systolic.roundToInt()}/${latest.diastolic.roundToInt()}",
                            chartRight - 4.dp.toPx(),
                            bloodPressureYAt(latest.systolic) - 4.dp.toPx(),
                            bloodPressurePaint,
                        )
                    }
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
        SelectedDaySummary(
            day = selectedDay,
            graphValues = selectedGraphValues,
            onAddManualRecord = onAddManualRecord,
        )
    }
}

@Composable
private fun SelectedDaySummary(
    day: ChartDaySelection?,
    graphValues: SelectedGraphValues?,
    onAddManualRecord: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp)
            .background(PopupBackground, RoundedCornerShape(8.dp))
            .padding(start = 10.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SummaryInfoLine(day?.let { it.date.formatMonthDayWithWeekday() } ?: "日付 -", ChartBlue, true)
            SummaryInfoLine(
                day?.let { "朝 ${it.morning.weightText()}  夜 ${it.night.weightText()}  差 ${it.weightDifferenceText()}" }
                    ?: "朝 -  夜 -  差 -",
                ChartBlue,
                true,
            )
            SummaryInfoLine("歩数 ${day?.steps?.steps?.let { "${it}歩" } ?: "-"}")
            SummaryInfoLine("血糖値 ${graphValues?.glucoseText ?: "-"}  A1c ${graphValues?.a1cText ?: "-"}")
            SummaryInfoLine("血圧 ${graphValues?.bloodPressureText ?: "-"}")
            SummaryInfoLine("腹囲 ${graphValues?.waistText ?: "-"}")
        }
        FloatingActionButton(
            onClick = onAddManualRecord,
            containerColor = AppPrimary,
            contentColor = Color.White,
            modifier = Modifier.size(46.dp),
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SummaryInfoLine(
    text: String,
    color: Color = AppText,
    bold: Boolean = false,
) {
    val displayText = buildAnnotatedString {
        text.split(ASSUMED_VALUE_SUFFIX).forEachIndexed { index, part ->
            if (index > 0) {
                withStyle(SpanStyle(fontSize = 10.sp)) {
                    append(ASSUMED_VALUE_SUFFIX)
                }
            }
            append(part)
        }
    }
    Text(
        text = displayText,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
        maxLines = 1,
    )
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
