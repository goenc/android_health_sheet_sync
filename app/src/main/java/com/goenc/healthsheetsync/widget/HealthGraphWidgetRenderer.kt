package com.goenc.healthsheetsync.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.goenc.healthsheetsync.data.StoredHealthData
import com.goenc.healthsheetsync.ui.A1C_CHART_MAX
import com.goenc.healthsheetsync.ui.A1C_CHART_MIN
import com.goenc.healthsheetsync.ui.AppBackground
import com.goenc.healthsheetsync.ui.AppPrimary
import com.goenc.healthsheetsync.ui.BLOOD_PRESSURE_CHART_HEIGHT_RATIO
import com.goenc.healthsheetsync.ui.BLOOD_PRESSURE_CHART_LINES
import com.goenc.healthsheetsync.ui.BLOOD_PRESSURE_CHART_MAX
import com.goenc.healthsheetsync.ui.BLOOD_PRESSURE_CHART_MIN
import com.goenc.healthsheetsync.ui.BloodPressureChartRecord
import com.goenc.healthsheetsync.ui.CHART_LEFT_PADDING_DP
import com.goenc.healthsheetsync.ui.CHART_RIGHT_PADDING_DP
import com.goenc.healthsheetsync.ui.CHART_WEIGHT_LOWER_PADDING_KG
import com.goenc.healthsheetsync.ui.CHART_WEIGHT_UPPER_PADDING_KG
import com.goenc.healthsheetsync.ui.ChartA1c
import com.goenc.healthsheetsync.ui.ChartBloodPressureArea
import com.goenc.healthsheetsync.ui.ChartBloodPressureDiastolic
import com.goenc.healthsheetsync.ui.ChartBloodPressureSystolic
import com.goenc.healthsheetsync.ui.ChartBlue
import com.goenc.healthsheetsync.ui.ChartGlucose
import com.goenc.healthsheetsync.ui.ChartGrid
import com.goenc.healthsheetsync.ui.ChartLabel
import com.goenc.healthsheetsync.ui.ChartMissingPoint
import com.goenc.healthsheetsync.ui.ChartStepBar
import com.goenc.healthsheetsync.ui.ChartStepText
import com.goenc.healthsheetsync.ui.ChartSummary
import com.goenc.healthsheetsync.ui.ChartTrend
import com.goenc.healthsheetsync.ui.ChartWaist
import com.goenc.healthsheetsync.ui.PopupBackground
import com.goenc.healthsheetsync.ui.STEP_CHART_MAX_STEPS
import com.goenc.healthsheetsync.ui.STEP_REFERENCE_STEPS
import com.goenc.healthsheetsync.ui.WAIST_CHART_LINES_CM
import com.goenc.healthsheetsync.ui.WAIST_CHART_GUIDE_MAX_CM
import com.goenc.healthsheetsync.ui.WAIST_CHART_MAX_CM
import com.goenc.healthsheetsync.ui.WAIST_CHART_MIN_CM
import com.goenc.healthsheetsync.ui.WAIST_CHART_TOP_RATIO
import com.goenc.healthsheetsync.ui.WeightChartRange
import com.goenc.healthsheetsync.ui.calculateA1cChart
import com.goenc.healthsheetsync.ui.calculateAverageSteps
import com.goenc.healthsheetsync.ui.calculateBloodPressureChart
import com.goenc.healthsheetsync.ui.calculateFastingGlucoseChart
import com.goenc.healthsheetsync.ui.calculateMissingWeightPoints
import com.goenc.healthsheetsync.ui.calculateStepBars
import com.goenc.healthsheetsync.ui.calculateTrendLine
import com.goenc.healthsheetsync.ui.calculateWaistChart
import com.goenc.healthsheetsync.ui.displayRecord
import com.goenc.healthsheetsync.ui.formatDecimal
import com.goenc.healthsheetsync.ui.formatTrendChange
import com.goenc.healthsheetsync.ui.isMorning
import com.goenc.healthsheetsync.ui.shouldShowChartDateNumber
import com.goenc.healthsheetsync.ui.toChartWeightPoints
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal object HealthGraphWidgetRenderer {
    fun render(
        context: Context,
        storedData: StoredHealthData,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val safeWidth = widthPx.coerceAtLeast(1)
        val safeHeight = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AppBackground.toArgb())

        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        val inset = (8f * density).coerceAtLeast(6f)
        val card = RectF(
            inset,
            inset,
            safeWidth - inset,
            safeHeight - inset,
        )
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PopupBackground.toArgb()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(card, 18f * density, 18f * density, cardPaint)

        val weightRecords = storedData.weightRecords.sortedBy { it.measuredAt }
        val latestAt = weightRecords.lastOrNull()?.measuredAt
        val range = WeightChartRange.TwoWeeks
        val window = range.window(weightRecords, latestAt)
        if (weightRecords.isEmpty() || window == null) {
            drawEmptyState(canvas, safeWidth, safeHeight, density, scaledDensity)
            return bitmap
        }

        val chartRecords = weightRecords.filter {
            !it.measuredAt.isBefore(window.startAt) && !it.measuredAt.isAfter(window.endAt)
        }
        val chartPoints = chartRecords.toChartWeightPoints()
        if (chartPoints.isEmpty()) {
            drawEmptyState(canvas, safeWidth, safeHeight, density, scaledDensity)
            return bitmap
        }

        val chartLeft = (CHART_LEFT_PADDING_DP * density).coerceAtLeast(32f)
        val chartRight = safeWidth - (CHART_RIGHT_PADDING_DP * density).coerceAtLeast(28f)
        val chartTop = (22f * density).coerceAtLeast(20f)
        val chartBottom = safeHeight - (24f * density).coerceAtLeast(22f)
        val chartWidth = max(1f, chartRight - chartLeft)
        val chartHeight = max(1f, chartBottom - chartTop)
        val minWeight = kotlin.math.floor(chartPoints.minOf { it.weightKg } - CHART_WEIGHT_LOWER_PADDING_KG)
        val maxWeight = ceil(chartPoints.maxOf { it.weightKg } + CHART_WEIGHT_UPPER_PADDING_KG)
        val weightRange = max(1.0, maxWeight - minWeight)
        val trendLine = calculateTrendLine(chartPoints)
        val averageSteps = calculateAverageSteps(storedData.stepDailyRecords, window)
        val glucoseChart = calculateFastingGlucoseChart(storedData.glucoseRecords, window)
        val a1cChart = calculateA1cChart(storedData.a1cDailyRecords, window)
        val waistChart = calculateWaistChart(storedData.manualRecords, window)
        val bloodPressureChart = calculateBloodPressureChart(storedData.manualRecords, window)
        val visibleDates = generateSequence(window.startAt.toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter(window.endAt.toLocalDate()) }
            .toList()

        fun xAtTime(measuredAt: LocalDateTime): Float {
            val totalMillis = max(1L, Duration.between(window.startAt, window.endAt).toMillis())
            val elapsedMillis = Duration.between(
                window.startAt,
                measuredAt.coerceIn(window.startAt, window.endAt),
            ).toMillis()
            return chartLeft + chartWidth * elapsedMillis.toFloat() / totalMillis
        }

        fun xAt(index: Int): Float = xAtTime(chartPoints[index].measuredAt)

        fun yAt(weightKg: Double): Float {
            val ratio = ((weightKg - minWeight) / weightRange).toFloat()
            return chartBottom - chartHeight * ratio
        }

        fun stepYAt(steps: Float): Float {
            val ratio = (steps / STEP_CHART_MAX_STEPS).coerceIn(0f, 1f)
            return chartBottom - chartHeight * ratio
        }

        fun a1cYAt(value: Double): Float {
            val ratio = ((value - A1C_CHART_MIN) / (A1C_CHART_MAX - A1C_CHART_MIN)).toFloat()
                .coerceIn(0f, 1f)
            return chartBottom - chartHeight * ratio
        }

        fun glucoseYAt(value: Double): Float {
            val chart = glucoseChart ?: return chartBottom - (54f * density)
            val ratio = ((value - chart.minValueMgDl) /
                (chart.maxValueMgDl - chart.minValueMgDl)).toFloat()
                .coerceIn(0f, 1f)
            return chartBottom - chartHeight * ratio
        }

        fun waistYAt(value: Double): Float {
            val waistTop = chartTop + chartHeight * WAIST_CHART_TOP_RATIO
            val waistHeight = max(1f, chartBottom - waistTop)
            val ratio = ((value - WAIST_CHART_MIN_CM) /
                (WAIST_CHART_GUIDE_MAX_CM - WAIST_CHART_MIN_CM)).toFloat()
                .coerceIn(
                    0f,
                    ((WAIST_CHART_MAX_CM - WAIST_CHART_MIN_CM) /
                        (WAIST_CHART_GUIDE_MAX_CM - WAIST_CHART_MIN_CM)).toFloat(),
                )
            return chartBottom - waistHeight * ratio
        }

        fun bloodPressureYAt(value: Double): Float {
            val bloodPressureBottom = chartTop + chartHeight * BLOOD_PRESSURE_CHART_HEIGHT_RATIO
            val bloodPressureHeight = max(1f, bloodPressureBottom - chartTop)
            val ratio = ((value - BLOOD_PRESSURE_CHART_MIN) /
                (BLOOD_PRESSURE_CHART_MAX - BLOOD_PRESSURE_CHART_MIN)).toFloat()
                .coerceIn(0f, 1f)
            return bloodPressureBottom - bloodPressureHeight * ratio
        }

        val labelCenterPaint = textPaint(scaledDensity, ChartLabel.toArgb(), 11f).apply {
            textAlign = Paint.Align.CENTER
        }
        val labelLeftPaint = textPaint(scaledDensity, ChartLabel.toArgb(), 11f).apply {
            textAlign = Paint.Align.LEFT
        }
        val labelRightPaint = textPaint(scaledDensity, ChartLabel.toArgb(), 11f).apply {
            textAlign = Paint.Align.RIGHT
        }
        val summaryPaint = textPaint(scaledDensity, ChartSummary.toArgb(), 12f).apply {
            textAlign = Paint.Align.LEFT
        }
        val glucosePaint = textPaint(scaledDensity, ChartGlucose.toArgb(), 11f).apply {
            textAlign = Paint.Align.RIGHT
        }
        val a1cPaint = textPaint(scaledDensity, ChartA1c.toArgb(), 11f).apply {
            textAlign = Paint.Align.RIGHT
        }
        val waistPaint = textPaint(scaledDensity, ChartWaist.toArgb(), 11f).apply {
            textAlign = Paint.Align.RIGHT
        }
        val bloodPressurePaint = textPaint(scaledDensity, ChartBloodPressureSystolic.toArgb(), 11f).apply {
            textAlign = Paint.Align.RIGHT
        }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartGrid.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        val dashedGridPaint = Paint(gridPaint).apply {
            pathEffect = DashPathEffect(floatArrayOf(4f * density, 5f * density), 0f)
        }
        val trendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartTrend.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        val weightLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartBlue.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val missingPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartMissingPoint.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val stepBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartStepBar.toArgb()
            style = Paint.Style.FILL
        }
        val glucoseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartGlucose.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        val a1cLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartA1c.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val waistLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartWaist.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val bloodPressureSystolicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartBloodPressureSystolic.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val bloodPressureDiastolicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartBloodPressureDiastolic.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val bloodPressureAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartBloodPressureArea.toArgb()
            style = Paint.Style.FILL
        }

        val solidWeightLines = generateSequence(maxWeight) { it - 1.0 }
            .takeWhile { it >= minWeight }
            .toList()
        val halfWeightLines = generateSequence(maxWeight - 0.5) { it - 1.0 }
            .takeWhile { it > minWeight }
            .toList()

        halfWeightLines.forEach { weightKg ->
            val y = yAt(weightKg)
            canvas.drawLine(chartLeft, y, chartRight, y, dashedGridPaint)
        }
        solidWeightLines.forEach { weightKg ->
            val y = yAt(weightKg)
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }
        WAIST_CHART_LINES_CM.forEach { waistCm ->
            val y = waistYAt(waistCm)
            canvas.drawLine(chartLeft, y, chartRight, y, dashedGridPaint)
        }
        BLOOD_PRESSURE_CHART_LINES.forEach { bloodPressure ->
            val y = bloodPressureYAt(bloodPressure)
            canvas.drawLine(chartLeft, y, chartRight, y, dashedGridPaint)
        }

        chartPoints.zipWithNext().forEach { (previous, current) ->
            if (previous.targetDate.dayOfWeek == DayOfWeek.SUNDAY &&
                current.targetDate.dayOfWeek == DayOfWeek.MONDAY
            ) {
                val x = (xAtTime(previous.measuredAt) + xAtTime(current.measuredAt)) / 2f
                canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
            }
        }

        val visibleStartDate = window.startAt.toLocalDate()
        val visibleStartMonthText = "${visibleStartDate.monthValue}月"
        canvas.drawText(visibleStartMonthText, chartLeft, chartBottom + (10f * density), labelLeftPaint)
        canvas.drawText(
            "1万",
            chartRight + (8f * density),
            stepYAt(STEP_REFERENCE_STEPS) - (8f * density),
            textPaint(scaledDensity, ChartStepText.toArgb(), 11f).apply {
                textAlign = Paint.Align.LEFT
            },
        )
        WAIST_CHART_LINES_CM.forEach { waistCm ->
            canvas.drawText(
                waistCm.toInt().toString(),
                chartRight + (8f * density),
                waistYAt(waistCm) + (4f * density),
                waistPaint,
            )
        }
        BLOOD_PRESSURE_CHART_LINES.forEach { bloodPressure ->
            canvas.drawText(
                bloodPressure.toInt().toString(),
                chartRight + (8f * density),
                bloodPressureYAt(bloodPressure) + (4f * density),
                bloodPressurePaint,
            )
        }

        val monthLabelMinX = chartLeft + labelLeftPaint.measureText(visibleStartMonthText) + (8f * density)
        val dateLabelPositions = visibleDates
            .filter { it.shouldShowChartDateNumber() }
            .map { date ->
                val x = xAtTime(date.atStartOfDay().plusHours(12))
                    .coerceIn(chartLeft + (6f * density), chartRight - (6f * density))
                val text = date.dayOfMonth.toString()
                ChartDateLabel(date, x, labelCenterPaint.measureText(text))
            }
        val hiddenDateLabels = dateLabelPositions
            .zipWithNext()
            .mapNotNull { (previous, current) ->
                if (previous.right + (4f * density) > current.left) previous.date else null
            }
            .toSet()
        visibleDates.forEach { date ->
            val x = xAtTime(date.atStartOfDay().plusHours(12))
                .coerceIn(chartLeft + (6f * density), chartRight - (6f * density))
            if (date.dayOfMonth == 1 && date != visibleStartDate && x >= monthLabelMinX) {
                canvas.drawText("${date.monthValue}月", x, chartBottom + (10f * density), labelLeftPaint)
            }
            if (date.shouldShowChartDateNumber() && date !in hiddenDateLabels) {
                canvas.drawText(date.dayOfMonth.toString(), x, chartBottom + (22f * density), labelCenterPaint)
            } else {
                canvas.drawCircle(
                    x,
                    chartBottom + (16f * density),
                    1f * density,
                    labelRightPaint,
                )
            }
        }

        trendLine?.let {
            val trendDuration = Duration.between(chartPoints.first().measuredAt, chartPoints.last().measuredAt)
            canvas.drawText(
                formatTrendChange(it, range, trendDuration),
                chartLeft + (8f * density),
                chartTop + (14f * density),
                summaryPaint,
            )
        }
        canvas.drawText(
            "平均歩数 ${averageSteps?.let { "%,d歩".format(it) } ?: "-"}",
            chartLeft + (8f * density),
            chartTop + (31f * density),
            summaryPaint,
        )

        calculateStepBars(storedData.stepDailyRecords, window).forEach { stepBar ->
            val stepRatio = (stepBar.steps.toFloat() / STEP_CHART_MAX_STEPS).coerceIn(0f, 1f)
            val barHeight = chartHeight * stepRatio
            val barWidth = 12f * density
            val left = xAtTime(stepBar.centerAt) - barWidth / 2f
            canvas.drawRect(left, chartBottom - barHeight, left + barWidth, chartBottom, stepBarPaint)
        }

        glucoseChart?.let { chart ->
            val linePoints = chart.lineRecords.map { record ->
                xAtTime(record.measuredAt) to glucoseYAt(record.bloodGlucoseMgDl)
            }
            drawPolyline(canvas, linePoints, glucoseLinePaint)
            chart.visibleRecords.forEach { record ->
                canvas.drawCircle(
                    xAtTime(record.measuredAt),
                    glucoseYAt(record.bloodGlucoseMgDl),
                    4f * density,
                    glucoseLinePaint,
                )
            }
            chart.displayRecord()?.let { latest ->
                val latestY = glucoseYAt(latest.bloodGlucoseMgDl)
                canvas.drawLine(xAtTime(latest.measuredAt), latestY, chartRight, latestY, glucoseLinePaint)
                canvas.drawText(
                    formatDecimal(latest.bloodGlucoseMgDl),
                    chartRight - (4f * density),
                    latestY - (4f * density),
                    glucosePaint(scaledDensity),
                )
            }
        }

        a1cChart?.let { chart ->
            drawPolyline(canvas, chart.lineRecords.map { record -> xAtTime(record.measuredAt) to a1cYAt(record.value) }, a1cLinePaint)
            chart.visibleRecords.forEach { record ->
                canvas.drawCircle(xAtTime(record.measuredAt), a1cYAt(record.value), 4f * density, a1cLinePaint)
            }
            chart.displayRecord()?.let { latest ->
                val latestY = a1cYAt(latest.value)
                canvas.drawLine(xAtTime(latest.measuredAt), latestY, chartRight, latestY, a1cLinePaint)
                canvas.drawText(
                    formatDecimal(latest.value),
                    chartRight - (4f * density),
                    latestY - (4f * density),
                    a1cPaint(scaledDensity),
                )
            }
        }

        waistChart?.let { chart ->
            drawPolyline(canvas, chart.lineRecords.map { record -> xAtTime(record.measuredAt) to waistYAt(record.value) }, waistLinePaint)
            chart.visibleRecords.forEach { record ->
                canvas.drawCircle(xAtTime(record.measuredAt), waistYAt(record.value), 4f * density, waistLinePaint)
            }
            chart.displayRecord()?.let { latest ->
                val latestY = waistYAt(latest.value)
                canvas.drawLine(xAtTime(latest.measuredAt), latestY, chartRight, latestY, waistLinePaint)
                canvas.drawText(
                    formatDecimal(latest.value),
                    chartRight - (4f * density),
                    latestY - (4f * density),
                    waistPaint,
                )
            }
        }

        bloodPressureChart?.let { chart ->
            val linePoints = chart.lineRecords
            if (linePoints.isNotEmpty()) {
                val areaPath = Path()
                linePoints.forEachIndexed { index, record ->
                    val x = xAtTime(record.measuredAt)
                    val y = bloodPressureYAt(record.systolic)
                    if (index == 0) areaPath.moveTo(x, y) else areaPath.lineTo(x, y)
                }
                linePoints.asReversed().forEach { record ->
                    areaPath.lineTo(xAtTime(record.measuredAt), bloodPressureYAt(record.diastolic))
                }
                areaPath.close()
                canvas.drawPath(areaPath, bloodPressureAreaPaint)
                drawPolyline(canvas, linePoints.map { record -> xAtTime(record.measuredAt) to bloodPressureYAt(record.systolic) }, bloodPressureSystolicPaint)
                drawPolyline(canvas, linePoints.map { record -> xAtTime(record.measuredAt) to bloodPressureYAt(record.diastolic) }, bloodPressureDiastolicPaint)
            }
            chart.visibleRecords.forEach { record ->
                val x = xAtTime(record.measuredAt)
                canvas.drawCircle(x, bloodPressureYAt(record.systolic), 3.5f * density, bloodPressureSystolicPaint)
                canvas.drawCircle(x, bloodPressureYAt(record.diastolic), 3.5f * density, bloodPressureDiastolicPaint)
            }
            chart.latestRecord?.let { latest ->
                canvas.drawText(
                    "${latest.systolic.roundToInt()}/${latest.diastolic.roundToInt()}",
                    chartRight - (4f * density),
                    bloodPressureYAt(latest.systolic) - (4f * density),
                    bloodPressurePaint(scaledDensity),
                )
            }
        }

        val weightPath = Path()
        chartPoints.forEachIndexed { index, record ->
            val pointX = xAt(index)
            val pointY = yAt(record.weightKg)
            if (index == 0) {
                weightPath.moveTo(pointX, pointY)
            } else {
                weightPath.lineTo(pointX, pointY)
            }
        }
        canvas.drawPath(weightPath, weightLinePaint)

        calculateMissingWeightPoints(chartPoints).forEach { missingPoint ->
            canvas.drawCircle(
                xAtTime(missingPoint.measuredAt),
                yAt(missingPoint.weightKg),
                4f * density,
                missingPointPaint,
            )
        }

        trendLine?.let {
            canvas.drawLine(
                xAt(0),
                yAt(it.startWeightKg),
                xAt(chartPoints.lastIndex),
                yAt(it.endWeightKg),
                trendPaint,
            )
        }

        chartPoints.forEachIndexed { index, record ->
            val centerX = xAt(index)
            val centerY = yAt(record.weightKg)
            if (record.isMorning()) {
                canvas.drawCircle(centerX, centerY, 4f * density, weightLinePaint)
            } else {
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = AppBackground.toArgb()
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(centerX, centerY, 4f * density, fillPaint)
                canvas.drawCircle(centerX, centerY, 4f * density, weightLinePaint)
            }
        }

        return bitmap
    }

    private fun drawEmptyState(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        scaledDensity: Float,
    ) {
        val titlePaint = textPaint(scaledDensity, ChartSummary.toArgb(), 12f).apply {
            textAlign = Paint.Align.CENTER
        }
        val bodyPaint = textPaint(scaledDensity, ChartLabel.toArgb(), 11f).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "データがありません",
            widthPx / 2f,
            heightPx / 2f - (6f * density),
            titlePaint,
        )
        canvas.drawText(
            "アプリで同期すると表示されます",
            widthPx / 2f,
            heightPx / 2f + (12f * density),
            bodyPaint,
        )
    }

    private fun textPaint(scaledDensity: Float, color: Int, sp: Float): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sp * scaledDensity
        }
    }

    private fun glucosePaint(scaledDensity: Float): Paint = textPaint(scaledDensity, ChartGlucose.toArgb(), 11f).apply {
        textAlign = Paint.Align.RIGHT
    }

    private fun a1cPaint(scaledDensity: Float): Paint = textPaint(scaledDensity, ChartA1c.toArgb(), 11f).apply {
        textAlign = Paint.Align.RIGHT
    }

    private fun bloodPressurePaint(scaledDensity: Float): Paint = textPaint(scaledDensity, ChartBloodPressureSystolic.toArgb(), 11f).apply {
        textAlign = Paint.Align.RIGHT
    }

    private fun drawPolyline(canvas: Canvas, points: List<Pair<Float, Float>>, paint: Paint) {
        if (points.isEmpty()) return
        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) {
                path.moveTo(point.first, point.second)
            } else {
                path.lineTo(point.first, point.second)
            }
        }
        canvas.drawPath(path, paint)
    }
}

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
