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
import androidx.compose.ui.graphics.Color
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
import com.goenc.healthsheetsync.health.ManualHealthRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import java.util.Locale

private val FfmiColor = Color(0xFF248064)
private val RatioColor = Color(0xFFB56B19)
private fun ratioText(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

@Composable
internal fun IndicesScreen(
    records: List<DebugWeightRecord>,
    dailyBodySettings: List<DailyBodySetting>,
    manualRecords: List<ManualHealthRecord>,
    onBack: () -> Unit,
) {
    val points = remember(records, dailyBodySettings, manualRecords) {
        dailyIndexPoints(records, dailyBodySettings, manualRecords)
    }
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
        Text("BMI・FFMI・ウエスト身長比の推移", style = MaterialTheme.typography.titleMedium, color = AppText)
        Text("朝と夜の平均体重で算出。片方だけの日はその体重を使用。", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        Text("FFMIは腹囲・首回りから体脂肪率を推定して算出。腹囲・首回りは各日以前の最新記録を使用。",
            style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(WeightChartRange.TwoWeeks, WeightChartRange.OneMonth, WeightChartRange.ThreeMonths, WeightChartRange.All).forEach { option ->
                FilterChip(selected = range == option, onClick = { range = option; selectedDate = null },
                    label = { Text(option.label) })
            }
        }
        if (selected == null || start == null) {
            Text("指数を表示できるデータがありません。体重・腹囲・首回りと、その日以前の身長設定を確認してください。", color = AppMutedBlue)
        } else {
            Text(selected.date.format(DateTimeFormatter.ofPattern("yyyy/M/d")), color = AppText,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("BMI ${selected.bmi?.let(::formatDecimal) ?: "—"}", color = ChartBlue)
                Text("FFMI ${selected.ffmi?.let(::formatDecimal) ?: "—"}", color = FfmiColor)
            }
            Text("ウエスト身長比（WHtR） ${selected.waistHeightRatio?.let(::ratioText) ?: "—"}", color = RatioColor)
            Text("左軸：BMI・FFMI　右軸：WHtR", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
            IndexTrendChart(visiblePoints, start, end, selected, onSelect = { selectedDate = it.date })
            Text("グラフをタップすると、その日の指数を表示。— は算出に必要な記録なし。",
                style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        }
    }
}

@Composable
private fun IndexTrendChart(
    points: List<IndexChartPoint>,
    start: LocalDate,
    end: LocalDate,
    selected: IndexChartPoint,
    onSelect: (IndexChartPoint) -> Unit,
) {
    fun bounds(values: List<Double>, minimumPadding: Double): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 1.0
        val minimum = values.min()
        val maximum = values.max()
        val padding = ((maximum - minimum) * 0.15).coerceAtLeast(minimumPadding)
        return (minimum - padding).coerceAtLeast(0.0) to maximum + padding
    }
    val leftValues = points.flatMap { listOfNotNull(it.bmi, it.ffmi) }
    val rightValues = points.mapNotNull { it.waistHeightRatio }
    val (lower, upper) = bounds(leftValues, 0.5)
    val (ratioLower, ratioUpper) = bounds(rightValues, 0.02)
    val firstDay = start.toEpochDay()
    val daySpan = (end.toEpochDay() - firstDay).coerceAtLeast(1)
    val description = "指数の推移。${selected.date}、BMI ${selected.bmi?.let(::formatDecimal) ?: "記録なし"}、" +
        "FFMI ${selected.ffmi?.let(::formatDecimal) ?: "記録なし"}、WHtR ${selected.waistHeightRatio?.let(::ratioText) ?: "記録なし"}"
    Canvas(Modifier.fillMaxWidth().height(320.dp)
        .semantics { contentDescription = description }
        .pointerInput(points, start, end) {
            detectTapGestures { touch ->
                val left = 48.dp.toPx()
                val width = size.width - left - 48.dp.toPx()
                points.minByOrNull {
                    val x = left + width * (it.date.toEpochDay() - firstDay).toFloat() / daySpan
                    abs(touch.x - x)
                }?.let(onSelect)
            }
        }) {
        val left = 48.dp.toPx()
        val right = size.width - 48.dp.toPx()
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
            if (leftValues.isNotEmpty()) {
                labelPaint.textAlign = Paint.Align.RIGHT
                labelPaint.color = AppMutedBlue.toArgb()
                drawContext.canvas.nativeCanvas.drawText(formatDecimal(lower + (upper - lower) * ratio),
                    left - 6.dp.toPx(), y + labelPaint.textSize / 3, labelPaint)
            }
            if (rightValues.isNotEmpty()) {
                labelPaint.textAlign = Paint.Align.LEFT
                labelPaint.color = RatioColor.toArgb()
                drawContext.canvas.nativeCanvas.drawText(ratioText(ratioLower + (ratioUpper - ratioLower) * ratio),
                    right + 6.dp.toPx(), y + labelPaint.textSize / 3, labelPaint)
            }
        }
        val series = listOf<Pair<Color, (IndexChartPoint) -> Double?>>(
            ChartBlue to { it.bmi }, FfmiColor to { it.ffmi }, RatioColor to { it.waistHeightRatio },
        )
        series.forEachIndexed { seriesIndex, (color, valueOf) ->
            val min = if (seriesIndex == 2) ratioLower else lower
            val max = if (seriesIndex == 2) ratioUpper else upper
            val path = Path()
            var connected = false
            points.forEach { point ->
                val value = valueOf(point)
                if (value == null) {
                    connected = false
                } else {
                    val offset = Offset(
                        left + (right - left) * (point.date.toEpochDay() - firstDay).toFloat() / daySpan,
                        bottom - (bottom - top) * ((value - min) / (max - min)).toFloat(),
                    )
                    if (connected) path.lineTo(offset.x, offset.y) else path.moveTo(offset.x, offset.y)
                    connected = true
                    if (point == selected) drawCircle(color.copy(alpha = 0.25f), 9.dp.toPx(), offset)
                    drawCircle(color, 3.dp.toPx(), offset)
                }
            }
            drawPath(path, color, style = Stroke(2.dp.toPx()))
        }
        val dateFormat = DateTimeFormatter.ofPattern("yyyy/M/d")
        labelPaint.color = AppMutedBlue.toArgb()
        labelPaint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(start.format(dateFormat), left, size.height - 8.dp.toPx(), labelPaint)
        if (start != end) {
            labelPaint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(end.format(dateFormat), right, size.height - 8.dp.toPx(), labelPaint)
        }
    }
}
