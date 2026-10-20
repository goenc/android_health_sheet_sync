package com.goenc.healthsheetsync.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.DebugWeightRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
internal fun IndicesScreen(
    records: List<DebugWeightRecord>,
    dailyBodySettings: List<DailyBodySetting>,
    onBack: () -> Unit,
) {
    val points = remember(records, dailyBodySettings) { dailyBmiPoints(records, dailyBodySettings) }
    var range by remember { mutableStateOf(WeightChartRange.OneMonth) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val end = points.lastOrNull()?.date
    val start = end?.let {
        when (range) {
            WeightChartRange.TwoWeeks -> it.minusWeeks(2)
            WeightChartRange.OneMonth -> it.minusMonths(1)
            WeightChartRange.ThreeMonths -> it.minusMonths(3)
            else -> points.first().date
        }
    }
    val visiblePoints = remember(points, start) { points.filter { start == null || !it.date.isBefore(start) } }
    val selected = visiblePoints.firstOrNull { it.date == selectedDate } ?: visiblePoints.lastOrNull()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackIconButton(onBack)
            Text("指数", style = MaterialTheme.typography.titleLarge, color = AppText)
        }
        Text("BMIの推移", style = MaterialTheme.typography.titleMedium, color = AppText)
        Text("朝と夜の平均体重で算出。片方だけの日はその体重を使用。", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(WeightChartRange.TwoWeeks, WeightChartRange.OneMonth, WeightChartRange.ThreeMonths, WeightChartRange.All).forEach { option ->
                FilterChip(selected = range == option, onClick = { range = option; selectedDate = null },
                    label = { Text(option.label) })
            }
        }
        if (selected == null || start == null) {
            Text("BMIを表示できるデータがありません。体重と、その日以前の身長設定を確認してください。", color = AppMutedBlue)
        } else {
            Text("${selected.date.format(DateTimeFormatter.ofPattern("yyyy/M/d"))}　BMI ${formatDecimal(selected.value)}",
                color = ChartBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            BmiTrendChart(visiblePoints, start, end, selected, onSelect = { selectedDate = it.date })
            Text("グラフをタップすると、その日のBMIを表示。", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        }
    }
}

@Composable
private fun BmiTrendChart(
    points: List<BmiChartPoint>,
    start: LocalDate,
    end: LocalDate,
    selected: BmiChartPoint,
    onSelect: (BmiChartPoint) -> Unit,
) {
    val minimum = points.minOf { it.value }
    val maximum = points.maxOf { it.value }
    val padding = ((maximum - minimum) * 0.15).coerceAtLeast(0.5)
    val lower = (minimum - padding).coerceAtLeast(0.0)
    val upper = maximum + padding
    val firstDay = start.toEpochDay()
    val daySpan = (end.toEpochDay() - firstDay).coerceAtLeast(1)
    val description = "BMIの推移。${selected.date}、BMI ${formatDecimal(selected.value)}"
    Canvas(Modifier.fillMaxWidth().height(320.dp)
        .semantics { contentDescription = description }
        .pointerInput(points, start, end) {
            detectTapGestures { touch ->
                val left = 48.dp.toPx()
                val width = size.width - left - 12.dp.toPx()
                points.minByOrNull {
                    val x = left + width * (it.date.toEpochDay() - firstDay).toFloat() / daySpan
                    abs(touch.x - x)
                }?.let(onSelect)
            }
        }) {
        val left = 48.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 16.dp.toPx()
        val bottom = size.height - 32.dp.toPx()
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AppMutedBlue.toArgb()
            textSize = 11.sp.toPx()
            textAlign = Paint.Align.RIGHT
        }
        repeat(5) { index ->
            val ratio = index / 4f
            val y = bottom - (bottom - top) * ratio
            drawLine(ChartGrid, Offset(left, y), Offset(right, y), 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(formatDecimal(lower + (upper - lower) * ratio),
                left - 6.dp.toPx(), y + labelPaint.textSize / 3, labelPaint)
        }
        val offsets = points.map { point ->
            Offset(
                left + (right - left) * (point.date.toEpochDay() - firstDay).toFloat() / daySpan,
                bottom - (bottom - top) * ((point.value - lower) / (upper - lower)).toFloat(),
            )
        }
        val path = Path().apply {
            moveTo(offsets.first().x, offsets.first().y)
            offsets.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, ChartBlue, style = Stroke(2.dp.toPx()))
        points.forEachIndexed { index, point ->
            if (point == selected) drawCircle(AppPrimary.copy(alpha = 0.25f), 9.dp.toPx(), offsets[index])
            drawCircle(ChartBlue, 3.dp.toPx(), offsets[index])
        }
        val dateFormat = DateTimeFormatter.ofPattern("yyyy/M/d")
        labelPaint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(start.format(dateFormat), left, size.height - 8.dp.toPx(), labelPaint)
        if (start != end) {
            labelPaint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(end.format(dateFormat), right, size.height - 8.dp.toPx(), labelPaint)
        }
    }
}
