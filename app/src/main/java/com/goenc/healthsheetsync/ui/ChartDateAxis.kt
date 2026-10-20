package com.goenc.healthsheetsync.ui

import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDateTime

internal fun DrawScope.drawChartDateAxis(
    window: ChartTimeWindow,
    range: WeightChartRange,
    left: Float,
    right: Float,
    bottom: Float,
) {
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ChartLabel.toArgb()
        textSize = 12.sp.toPx()
        textAlign = Paint.Align.CENTER
    }
    val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ChartLabel.toArgb()
        textSize = 9.sp.toPx()
        textAlign = Paint.Align.LEFT
    }
    val duration = Duration.between(window.startAt, window.endAt).toMillis().coerceAtLeast(1)
    fun xAt(time: LocalDateTime): Float = (left + (right - left) *
        Duration.between(window.startAt, time).toMillis().toFloat() / duration)
        .coerceIn(left + 6.dp.toPx(), right - 6.dp.toPx())
    val startDate = window.startAt.toLocalDate()
    val monthText = "${startDate.monthValue}月"
    val monthMinX = left + monthPaint.measureText(monthText) + 8.dp.toPx()
    val dates = generateSequence(startDate) { it.plusDays(1) }
        .takeWhile { !it.isAfter(window.endAt.toLocalDate()) }.toList()
    val labels = dates.filter { it.shouldShowChartDateNumber() }.map { date ->
        ChartDateLabel(date, xAt(date.atTime(12, 0)), labelPaint.measureText(date.dayOfMonth.toString()))
    }
    val hiddenLabels = labels.zipWithNext().mapNotNull { (previous, current) ->
        if (previous.right + 4.dp.toPx() > current.left) previous.date else null
    }.toSet()
    drawContext.canvas.nativeCanvas.apply {
        drawText(monthText, left, bottom + 11.dp.toPx(), monthPaint)
        dates.forEach { date ->
            val x = xAt(date.atTime(12, 0))
            if (date.dayOfMonth == 1 && date != startDate && x >= monthMinX) {
                drawText("${date.monthValue}月", x, bottom + 11.dp.toPx(), monthPaint)
            }
            if (!range.isLongRange && date.shouldShowChartDateNumber() && date !in hiddenLabels) {
                drawText(date.dayOfMonth.toString(), x, bottom + 22.dp.toPx(), labelPaint)
            } else {
                drawCircle(x, bottom + 17.dp.toPx(), if (range.isLongRange) 0.8.dp.toPx() else 1.dp.toPx(), labelPaint)
            }
        }
    }
}
