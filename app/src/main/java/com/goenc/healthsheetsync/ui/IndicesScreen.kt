package com.goenc.healthsheetsync.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.DayOfWeek
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
    var range by remember { mutableStateOf(WeightChartRange.TwoWeeks) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showExplanation by remember { mutableStateOf(false) }
    val dates = remember(points) { points.map { it.date } }
    val latestEndAt = dates.lastOrNull()?.atStartOfDay()
    val earliestEndAt = remember(dates, range) { range.minimumEndAtForDates(dates) }
    var chartEndAt by remember(dates, range) { mutableStateOf(latestEndAt) }
    val window = remember(dates, range, chartEndAt) { range.windowForDates(dates, chartEndAt) }
    val visiblePoints = remember(points, window) {
        points.filter { point -> window != null && !point.date.atTime(12, 0).isBefore(window.startAt) &&
            !point.date.atTime(12, 0).isAfter(window.endAt) }
    }
    val selected = visiblePoints.firstOrNull { it.date == selectedDate } ?: visiblePoints.lastOrNull()
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackIconButton(onBack)
            Text("指数", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = AppText)
            TextButton(onClick = { showExplanation = true }) { Text("説明") }
        }
        Text("BMI・FFMI・ウエスト身長比の推移", style = MaterialTheme.typography.titleMedium, color = AppText)
        Text("BMI・FFMI：左軸　WHtR：右軸", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        if (window != null) {
            IndexTrendChart(visiblePoints, window, range, selected, chartEndAt, earliestEndAt, latestEndAt,
                onEndAtChange = { chartEndAt = it }, onSelect = { selectedDate = it.date },
                modifier = Modifier.weight(1f))
        }
        ChartRangeControls(range) { range = it; selectedDate = null }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected == null) {
                Text("指数を表示できるデータがありません。体重・腹囲・首回りと、その日以前の身長設定を確認してください。", color = AppMutedBlue)
            } else {
                Text(selected.date.format(DateTimeFormatter.ofPattern("yyyy/M/d")), color = AppText,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IndexValueWithInputs("BMI", selected.bmi, selected.weightKg?.let { "${formatDecimal(it)}kg" },
                        ChartBlue, Modifier.weight(1f))
                    val ffmiInputs = if (selected.ffmi != null) {
                        "${selected.weightKg?.let(::formatDecimal)}kg・${selected.waistCm?.let(::formatDecimal)}cm"
                    } else null
                    IndexValueWithInputs("FFMI", selected.ffmi, ffmiInputs, FfmiColor, Modifier.weight(1f))
                }
                Text(buildAnnotatedString {
                    append("ウエスト身長比（WHtR） ${selected.waistHeightRatio?.let(::ratioText) ?: "—"}")
                    selected.waistCm?.takeIf { selected.waistHeightRatio != null }?.let { waist ->
                        withStyle(SpanStyle(fontSize = 11.sp)) { append(" (${formatDecimal(waist)}cm)") }
                    }
                }, color = RatioColor)
            }
        }
    }
    if (showExplanation) {
        AlertDialog(
            onDismissRequest = { showExplanation = false },
            title = { Text("指数の説明") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("BMI・FFMIは朝と夜の平均体重で算出。片方だけの日はその体重を使用。")
                    Text("FFMIは腹囲・首回りから体脂肪率を推定して算出。腹囲・首回りは各日以前の最新記録を使用。")
                    Text("ウエスト身長比（WHtR）は腹囲÷身長。")
                    Text("横ドラッグで期間を移動。タップでその日の指数を表示。— は算出に必要な記録なし。")
                }
            },
            confirmButton = { TextButton(onClick = { showExplanation = false }) { Text("閉じる") } },
        )
    }
}

@Composable
private fun IndexValueWithInputs(label: String, value: Double?, inputs: String?, color: Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Text("$label ${value?.let(::formatDecimal) ?: "—"}", color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("(${inputs ?: "—"})", color = color, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IndexTrendChart(
    points: List<IndexChartPoint>,
    window: ChartTimeWindow,
    range: WeightChartRange,
    selected: IndexChartPoint?,
    chartEndAt: LocalDateTime?,
    earliestEndAt: LocalDateTime?,
    latestEndAt: LocalDateTime?,
    onEndAtChange: (LocalDateTime?) -> Unit,
    onSelect: (IndexChartPoint) -> Unit,
    modifier: Modifier = Modifier,
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
    val totalMillis = Duration.between(window.startAt, window.endAt).toMillis().coerceAtLeast(1)
    fun fractionAt(time: LocalDateTime): Float = Duration.between(window.startAt, time).toMillis().toFloat() / totalMillis
    val currentEndAt by rememberUpdatedState(chartEndAt)
    val currentOnEndAtChange by rememberUpdatedState(onEndAtChange)
    val description = "指数の推移。表示期間 ${window.startAt.toLocalDate()}～${window.endAt.toLocalDate()}。" +
        "${selected?.date ?: "選択なし"}、BMI ${selected?.bmi?.let(::formatDecimal) ?: "記録なし"}、" +
        "FFMI ${selected?.ffmi?.let(::formatDecimal) ?: "記録なし"}、WHtR ${selected?.waistHeightRatio?.let(::ratioText) ?: "記録なし"}"
    Canvas(modifier.fillMaxWidth()
        .semantics { contentDescription = description }
        .pointerInput(earliestEndAt, latestEndAt) {
            detectHorizontalDragGestures { _, dragAmount ->
                currentOnEndAtChange(chartEndAtAfterHorizontalDrag(
                    currentEndAt, earliestEndAt, latestEndAt, dragAmount, size.width.toFloat(),
                ))
            }
        }
        .pointerInput(points, window) {
            detectTapGestures { touch ->
                val left = 48.dp.toPx()
                val width = size.width - left - 48.dp.toPx()
                points.minByOrNull {
                    val x = left + width * fractionAt(it.date.atTime(12, 0))
                    abs(touch.x - x)
                }?.let(onSelect)
            }
        }) {
        val left = 48.dp.toPx()
        val right = size.width - 48.dp.toPx()
        val top = 16.dp.toPx()
        val bottom = size.height - 32.dp.toPx()
        fun xAt(time: LocalDateTime): Float = left + (right - left) * fractionAt(time)
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
                        xAt(point.date.atTime(12, 0)),
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
        selected?.let { point ->
            val x = xAt(point.date.atTime(12, 0))
            drawLine(AppPrimary, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
        }
        val visibleDates = generateSequence(window.startAt.toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter(window.endAt.toLocalDate()) }.toList()
        visibleDates.filter { it.dayOfWeek == DayOfWeek.MONDAY }.forEach { date ->
            val x = xAt(date.atStartOfDay())
            drawLine(ChartGrid, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
        }
        drawChartDateAxis(window, range, left, right, bottom)
    }
}
