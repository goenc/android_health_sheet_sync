package com.goenc.healthsheetsync.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.data.LabResult
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal data class LabChartPoint(val record: LabResult, val value: Double)

internal fun labChartPoints(records: List<LabResult>): List<LabChartPoint> = records
    .mapNotNull { record ->
        val text = record.value.trim()
        val normalized = if (text.matches(Regex("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d+)?"))) text.replace(",", "") else text
        normalized.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { LabChartPoint(record, it) }
    }.sortedBy { it.record.targetDate }

internal fun labChartBounds(points: List<LabChartPoint>): Pair<Double, Double> {
    val minimum = points.minOf { it.value }
    val maximum = points.maxOf { it.value }
    val padding = ((maximum - minimum) * 0.15).takeIf { it > 0.0 }
        ?: (abs(minimum) * 0.1).coerceAtLeast(0.1)
    return minimum - padding to maximum + padding
}

@Composable
internal fun LabResultChart(
    records: List<LabResult>,
    selectedRecord: LabResult?,
    onSelectRecord: (LabResult) -> Unit,
) {
    val points = remember(records) { labChartPoints(records) }
    if (points.isEmpty()) {
        Text("数値の推移グラフはありません", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        return
    }
    val bounds = remember(points) { labChartBounds(points) }
    val firstDay = points.first().record.targetDate.toEpochDay()
    val lastDay = points.last().record.targetDate.toEpochDay()
    val errorColor = MaterialTheme.colorScheme.error
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy/M/d") }
    val description = points.joinToString("、") { "${it.record.targetDate} ${it.record.value} ${it.record.unit}" }

    Canvas(Modifier.fillMaxWidth().height(210.dp)
        .semantics { contentDescription = "検査値の推移。$description" }
        .pointerInput(points, bounds) {
            detectTapGestures { touch ->
                val left = 54.dp.toPx()
                val right = size.width - 14.dp.toPx()
                val top = 18.dp.toPx()
                val bottom = size.height - 30.dp.toPx()
                val nearest = points.minByOrNull { point ->
                    val x = if (firstDay == lastDay) (left + right) / 2f else
                        left + (right - left) * ((point.record.targetDate.toEpochDay() - firstDay).toDouble() / (lastDay - firstDay)).toFloat()
                    val y = bottom - (bottom - top) * ((point.value - bounds.first) / (bounds.second - bounds.first)).toFloat()
                    (touch.x - x) * (touch.x - x) + (touch.y - y) * (touch.y - y)
                }
                nearest?.let { onSelectRecord(it.record) }
            }
        }) {
        val left = 54.dp.toPx()
        val right = size.width - 14.dp.toPx()
        val top = 18.dp.toPx()
        val bottom = size.height - 30.dp.toPx()
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AppMutedBlue.toArgb()
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.RIGHT
        }
        repeat(5) { index ->
            val ratio = index / 4.0
            val y = bottom - (bottom - top) * ratio.toFloat()
            drawLine(ChartGrid, Offset(left, y), Offset(right, y), 1.dp.toPx())
            val value = bounds.first + (bounds.second - bounds.first) * ratio
            val label = String.format(Locale.US, "%.3g", value)
            drawContext.canvas.nativeCanvas.drawText(label, left - 6.dp.toPx(), y + labelPaint.textSize / 3f, labelPaint)
        }
        val offsets = points.map { point ->
            val x = if (firstDay == lastDay) (left + right) / 2f else
                left + (right - left) * ((point.record.targetDate.toEpochDay() - firstDay).toDouble() / (lastDay - firstDay)).toFloat()
            val y = bottom - (bottom - top) * ((point.value - bounds.first) / (bounds.second - bounds.first)).toFloat()
            Offset(x, y)
        }
        if (offsets.size > 1) {
            val path = Path().apply {
                moveTo(offsets.first().x, offsets.first().y)
                offsets.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, ChartBlue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
        points.forEachIndexed { index, point ->
            if (point.record == selectedRecord) drawCircle(AppPrimary.copy(alpha = 0.25f), 9.dp.toPx(), offsets[index])
            drawCircle(if (point.record.flag.isNotBlank()) errorColor else ChartBlue, 4.dp.toPx(), offsets[index])
        }
        labelPaint.textAlign = if (firstDay == lastDay) Paint.Align.CENTER else Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(points.first().record.targetDate.format(dateFormat),
            if (firstDay == lastDay) (left + right) / 2f else left, size.height - 8.dp.toPx(), labelPaint)
        if (firstDay != lastDay) {
            labelPaint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(points.last().record.targetDate.format(dateFormat), right, size.height - 8.dp.toPx(), labelPaint)
        }
    }
}
