package com.goenc.healthsheetsync.ui

import android.content.Context
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.R
import com.goenc.healthsheetsync.data.BodyCompositionMeasurement
import com.goenc.healthsheetsync.data.BodyCompositionMetric
import com.goenc.healthsheetsync.data.BodyCompositionValue
import com.goenc.healthsheetsync.data.LabResultsUiState
import com.goenc.healthsheetsync.data.BODY_COMPOSITION_METRICS
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private const val BodyItemColumnWeight = 1.8f
private const val BodyValueColumnWeight = 1f
private val BodyTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)

@Composable
internal fun BodyCompositionScreen(
    state: LabResultsUiState,
    onRefresh: () -> Unit,
    onImport: (List<BodyCompositionMeasurement>, (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectTab: (Int) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context) { BodyCompositionDisplaySettingsStore(context.applicationContext) }
    var excludedMetricIds by remember { mutableStateOf(settings.excludedMetricIds()) }
    var showTargetOnly by remember { mutableStateOf(settings.showTargetOnly()) }
    var comparisonCount by remember { mutableStateOf(settings.comparisonCount()) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var selectedMetricId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMeasurementId by rememberSaveable(selectedMetricId) { mutableStateOf<String?>(null) }
    var selectedMeasurementWindowAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    val comparisonListState = rememberLazyListState()
    val historyListState = rememberLazyListState()

    BackHandler(enabled = showSettings || selectedMetricId != null) {
        if (showSettings) showSettings = false else selectedMetricId = null
    }
    if (showSettings) {
        if (showImport) {
            BodyCompositionImportScreen(state, onImport, onBack = { showImport = false }, modifier = modifier)
        } else {
            BodyCompositionSettingsScreen(
                state = state,
                excludedMetricIds = excludedMetricIds,
                comparisonCount = comparisonCount,
                onComparisonCountChanged = {
                    comparisonCount = it
                    settings.saveComparisonCount(it)
                },
                onMetricChecked = { metricId, checked ->
                    excludedMetricIds = if (checked) excludedMetricIds - metricId else excludedMetricIds + metricId
                    settings.saveExcludedMetricIds(excludedMetricIds)
                },
                onRefresh = onRefresh,
                onOpenImport = { showImport = true },
                onBack = { showSettings = false },
                modifier = modifier,
            )
        }
        return
    }

    val measurements = remember(state.bodyMeasurements) {
        state.bodyMeasurements.sortedWith(bodyMeasurementDescendingComparator())
    }
    val visibleMetrics = remember(showTargetOnly, excludedMetricIds) {
        BODY_COMPOSITION_METRICS.filter { !showTargetOnly || it.id !in excludedMetricIds }
    }
    val selectedMetric = BODY_COMPOSITION_METRICS.firstOrNull { it.id == selectedMetricId }
    val rows = remember(measurements, selectedMetricId, selectedMetric) {
        if (selectedMetric == null) emptyList() else measurements.mapNotNull { measurement ->
            measurement.values.firstOrNull { it.metricId == selectedMetric.id }?.let { measurement to it }
        }
    }
    var selectedSeriesKey by rememberSaveable(selectedMetricId) { mutableStateOf<String?>(null) }
    val seriesKeys = remember(rows) {
        rows.map { (measurement, value) ->
            BodyCompositionSeriesKey(measurement.deviceModel, measurement.source, value.unit)
        }.distinct()
    }
    val effectiveSeriesKey = selectedSeriesKey?.let { encoded ->
        seriesKeys.firstOrNull { it.encoded == encoded }
    } ?: seriesKeys.firstOrNull()
    val detailRows = remember(rows, effectiveSeriesKey) {
        if (effectiveSeriesKey == null) emptyList() else rows.filter { (measurement, value) ->
            BodyCompositionSeriesKey(measurement.deviceModel, measurement.source, value.unit) == effectiveSeriesKey
        }.sortedWith { a, b -> bodyMeasurementAscendingComparator().compare(a.first, b.first) }
    }
    val selectedMeasurement = detailRows.firstOrNull { it.first.id == selectedMeasurementId }?.first

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            BackIconButton(onClick = { if (selectedMetricId != null) selectedMetricId = null else onBack() })
            Text(if (selectedMetric == null) "検査結果" else "${selectedMetric.name} の履歴",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { showSettings = true }) {
                Icon(painterResource(R.drawable.ic_settings_24), contentDescription = "体組成の設定")
            }
        }
        LabResultsTabRow(selectedTab = 1, onSelectTab = onSelectTab)
        BodyCompositionStatusText(state)
        if (state.isLoading) CircularProgressIndicator()
        if (selectedMetric == null) {
            if (measurements.isEmpty()) {
                Text("体組成データはまだありません。設定から写真を解析するか、JSONを取り込む。")
            } else {
                val maximumWindowStart = (measurements.size - comparisonCount).coerceAtLeast(0)
                val requestedWindowStart = measurements.indexOfFirst { it.id == selectedMeasurementWindowAnchor }
                    .takeIf { it >= 0 } ?: 0
                val windowStart = requestedWindowStart.coerceAtMost(maximumWindowStart)
                val comparison = measurements.drop(windowStart).take(comparisonCount)
                androidx.compose.runtime.LaunchedEffect(measurements, comparisonCount, windowStart) {
                    selectedMeasurementWindowAnchor = measurements.getOrNull(windowStart)?.id
                }
                LabResultsDateNavigation(
                    year = comparison.first().targetDate.year,
                    onPrevious = {
                        selectedMeasurementWindowAnchor = measurements.getOrNull((windowStart - 1).coerceAtLeast(0))?.id
                    },
                    onNext = {
                        selectedMeasurementWindowAnchor = measurements.getOrNull((windowStart + 1).coerceAtMost(maximumWindowStart))?.id
                    },
                    canPrevious = windowStart > 0,
                    canNext = windowStart < maximumWindowStart,
                )
                Row(Modifier.fillMaxWidth().bodyComparisonDividers(comparisonCount),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(BodyItemColumnWeight), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = !showTargetOnly, onClick = {
                            showTargetOnly = false
                            settings.saveShowTargetOnly(false)
                        }, label = { Text("全項目", fontSize = 10.sp, maxLines = 1) })
                        FilterChip(selected = showTargetOnly, onClick = {
                            showTargetOnly = true
                            settings.saveShowTargetOnly(true)
                        }, label = { Text("対象項目", fontSize = 10.sp, maxLines = 1) })
                    }
                    comparison.forEach { measurement ->
                        Text(bodyMeasurementColumnLabel(measurement, comparison),
                            modifier = Modifier.weight(BodyValueColumnWeight), textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold, fontSize = (16 - comparisonCount).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    repeat(comparisonCount - comparison.size) { Text("", modifier = Modifier.weight(BodyValueColumnWeight)) }
                }
                LazyColumn(
                    state = comparisonListState,
                    modifier = Modifier.weight(1f).pointerInput(measurements, windowStart, comparisonCount) {
                        var dragDistance = 0f
                        val threshold = 48.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { dragDistance = 0f },
                            onDragCancel = { dragDistance = 0f },
                            onDragEnd = {
                                val nextStart = when {
                                    dragDistance <= -threshold -> (windowStart + 1).coerceAtMost(maximumWindowStart)
                                    dragDistance >= threshold -> (windowStart - 1).coerceAtLeast(0)
                                    else -> windowStart
                                }
                                selectedMeasurementWindowAnchor = measurements.getOrNull(nextStart)?.id
                            },
                            onHorizontalDrag = { _, amount -> dragDistance += amount },
                        )
                    },
                ) {
                    if (visibleMetrics.isEmpty()) item { Text("対象項目がありません。設定で表示する項目を選択する。") }
                    items(visibleMetrics, key = { it.id }) { metric ->
                        BodyCompositionComparisonRow(metric, comparison, comparisonCount,
                            onClick = { selectedMetricId = metric.id })
                    }
                }
            }
        } else {
            selectedMetric.let { metric ->
                if (seriesKeys.size > 1) {
                    Text("機器・出所・単位ごとの系列", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        seriesKeys.forEach { key ->
                            FilterChip(selected = key == effectiveSeriesKey, onClick = {
                                selectedSeriesKey = key.encoded
                                selectedMeasurementId = null
                            }, label = { Text(key.label(), maxLines = 1) })
                        }
                    }
                }
                if (detailRows.isEmpty()) {
                    Text("この項目の測定履歴はありません")
                } else {
                    LazyColumn(state = historyListState, modifier = Modifier.weight(1f)) {
                        item {
                            BodyCompositionChart(
                                records = detailRows,
                                selectedMeasurement = selectedMeasurement,
                                onSelectMeasurement = { selectedMeasurementId = it.id },
                            )
                        }
                        items(detailRows.asReversed(), key = { it.first.id }) { (measurement, value) ->
                            BodyCompositionHistoryRow(measurement, value,
                                seriesMeasurements = detailRows.asReversed().map { it.first },
                                selected = measurement.id == selectedMeasurementId,
                                onClick = { selectedMeasurementId = measurement.id })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyCompositionSettingsScreen(
    state: LabResultsUiState,
    excludedMetricIds: Set<String>,
    comparisonCount: Int,
    onComparisonCountChanged: (Int) -> Unit,
    onMetricChecked: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenImport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackIconButton(onClick = onBack)
            Text("体組成の設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        BodyCompositionStatusText(state)
        OutlinedButton(onClick = onRefresh, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.isLoading) "更新中" else "更新")
        }
        Text("比較する回数", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { count ->
                FilterChip(selected = comparisonCount == count, onClick = { onComparisonCountChanged(count) },
                    label = { Text("${count}回") }, modifier = Modifier.weight(1f))
            }
        }
        Text("対象項目", fontWeight = FontWeight.Bold)
        Text("チェックした項目を「対象項目」で表示する。", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            items(BODY_COMPOSITION_METRICS, key = { it.id }) { metric ->
                val checked = metric.id !in excludedMetricIds
                Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                    onValueChange = { onMetricChecked(metric.id, it) }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(metric.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    if (metric.unit.isNotBlank()) Text(bodyCompositionDisplayUnit(metric.unit), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(color = DividerColor)
            }
        }
        BodyCompositionPhotoShareButton(enabled = !state.isLoading)
        OutlinedButton(onClick = onOpenImport, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
            Text("体組成データを取り込む")
        }
    }
}

@Composable
private fun BodyCompositionStatusText(state: LabResultsUiState) {
    state.status?.let { status ->
        val needsAttention = listOf("失敗", "未反映", "エラー", "できません")
            .any { keyword -> status.contains(keyword) }
        Text(status, style = MaterialTheme.typography.bodySmall,
            color = if (needsAttention) MaterialTheme.colorScheme.error else AppMutedBlue)
    }
}

@Composable
private fun BodyCompositionComparisonRow(
    metric: BodyCompositionMetric,
    measurements: List<BodyCompositionMeasurement>,
    comparisonCount: Int,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().bodyComparisonDividers(comparisonCount).padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(metric.name, modifier = Modifier.weight(BodyItemColumnWeight), fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            measurements.forEach { measurement ->
                val value = measurement.values.firstOrNull { it.metricId == metric.id }
                Text(value?.let { formatBodyValue(it) } ?: "—", modifier = Modifier.weight(BodyValueColumnWeight),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (value == null) AppMutedBlue else AppText)
            }
            repeat((comparisonCount - measurements.size).coerceAtLeast(0)) { Text("", modifier = Modifier.weight(BodyValueColumnWeight)) }
        }
        HorizontalDivider(color = DividerColor)
    }
}

@Composable
private fun BodyCompositionHistoryRow(
    measurement: BodyCompositionMeasurement,
    value: BodyCompositionValue,
    seriesMeasurements: List<BodyCompositionMeasurement>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()
        .background(if (selected) AppPrimary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bodyMeasurementDateLabel(measurement, seriesMeasurements), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(listOf(measurement.deviceModel, sourceDisplayName(measurement.source)).filter(String::isNotBlank)
                    .joinToString(" ・ ").ifBlank { "機器・出所不明" },
                    fontSize = 10.sp, color = AppMutedBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(formatBodyValue(value), color = AppText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = DividerColor)
    }
}

private data class BodyCompositionSeriesKey(val deviceModel: String, val source: String, val unit: String) {
    val encoded: String get() = listOf(deviceModel, source, unit).joinToString("\u001f")

    fun label(): String = listOf(
        deviceModel.ifBlank { "機器不明" },
        sourceDisplayName(source).ifBlank { "出所不明" },
        bodyCompositionDisplayUnit(unit).ifBlank { "単位なし" },
    ).joinToString("・")
}

private data class BodyChartRecord(
    val measurement: BodyCompositionMeasurement,
    val bodyValue: BodyCompositionValue,
    val number: Double,
    val xPosition: Double,
)

@Composable
private fun BodyCompositionChart(
    records: List<Pair<BodyCompositionMeasurement, BodyCompositionValue>>,
    selectedMeasurement: BodyCompositionMeasurement?,
    onSelectMeasurement: (BodyCompositionMeasurement) -> Unit,
) {
    val points = remember(records) {
        records.mapNotNull { (measurement, value) ->
            val normalized = value.value.trim().let { text ->
                if (text.matches(Regex("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d+)?"))) text.replace(",", "") else text
            }
            normalized.toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
                BodyChartRecord(measurement, value, it, bodyMeasurementXPosition(measurement))
            }
        }
    }
    if (points.isEmpty()) {
        Text("数値の推移グラフはありません", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        return
    }
    val minimum = points.minOf { it.number }
    val maximum = points.maxOf { it.number }
    val padding = ((maximum - minimum) * 0.15).takeIf { it > 0.0 } ?: (abs(minimum) * 0.1).coerceAtLeast(0.1)
    val minBound = minimum - padding
    val maxBound = maximum + padding
    val selectedId = selectedMeasurement?.id
    val seriesMeasurements = records.map { it.first }
    val description = points.joinToString("、") {
        "${bodyMeasurementDateLabel(it.measurement, seriesMeasurements)} ${formatBodyValue(it.bodyValue)}"
    }
    val firstX = points.first().xPosition
    val lastX = points.last().xPosition
    Canvas(Modifier.fillMaxWidth().height(210.dp)
        .semantics { contentDescription = "体組成値の推移。$description" }
        .pointerInput(points, minBound, maxBound) {
            detectTapGestures { touch ->
                val left = 54.dp.toPx()
                val right = size.width - 14.dp.toPx()
                val top = 18.dp.toPx()
                val bottom = size.height - 30.dp.toPx()
                points.indices.minByOrNull { index ->
                    val x = if (firstX == lastX) (left + right) / 2f else
                        left + (right - left) * ((points[index].xPosition - firstX) / (lastX - firstX)).toFloat()
                    val y = bottom - (bottom - top) * ((points[index].number - minBound) / (maxBound - minBound)).toFloat()
                    (touch.x - x) * (touch.x - x) + (touch.y - y) * (touch.y - y)
                }?.let { onSelectMeasurement(points[it].measurement) }
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
            val value = minBound + (maxBound - minBound) * ratio
            drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, "%.3g", value),
                left - 6.dp.toPx(), y + labelPaint.textSize / 3f, labelPaint)
        }
        val offsets = points.mapIndexed { index, point ->
            val x = if (firstX == lastX) (left + right) / 2f else
                left + (right - left) * ((point.xPosition - firstX) / (lastX - firstX)).toFloat()
            val y = bottom - (bottom - top) * ((point.number - minBound) / (maxBound - minBound)).toFloat()
            Offset(x, y)
        }
        if (offsets.size > 1) {
            val path = Path().apply {
                moveTo(offsets.first().x, offsets.first().y)
                offsets.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, ChartBlue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
        points.indices.sortedBy { points[it].measurement.id == selectedId }.forEach { index ->
            val point = points[index]
            if (point.measurement.id == selectedId) drawCircle(AppPrimary.copy(alpha = 0.25f), 9.dp.toPx(), offsets[index])
            drawCircle(ChartBlue, 4.dp.toPx(), offsets[index])
        }
        labelPaint.textAlign = if (points.size == 1) Paint.Align.CENTER else Paint.Align.LEFT
        val showTime = points.size > 1 && points.first().measurement.targetDate == points.last().measurement.targetDate
        drawContext.canvas.nativeCanvas.drawText(bodyMeasurementShortDateLabel(points.first().measurement, showTime),
            if (firstX == lastX) (left + right) / 2f else left, size.height - 8.dp.toPx(), labelPaint)
        if (points.size > 1) {
            labelPaint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(bodyMeasurementShortDateLabel(points.last().measurement, showTime), right, size.height - 8.dp.toPx(), labelPaint)
        }
    }
}

private class BodyCompositionDisplaySettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("body_composition_display_settings", Context.MODE_PRIVATE)

    fun excludedMetricIds(): Set<String> = preferences.getStringSet("excluded_metric_ids", null)?.toSet()
        ?: BODY_COMPOSITION_METRICS.filterNot { it.defaultVisible }.map { it.id }.toSet()

    fun saveExcludedMetricIds(ids: Set<String>) {
        preferences.edit().putStringSet("excluded_metric_ids", ids.toSet()).apply()
    }

    fun showTargetOnly(): Boolean = preferences.getBoolean("show_target_only", true)
    fun saveShowTargetOnly(enabled: Boolean) { preferences.edit().putBoolean("show_target_only", enabled).apply() }
    fun comparisonCount(): Int = preferences.getInt("comparison_count", 3).coerceIn(1, 3)
    fun saveComparisonCount(count: Int) { preferences.edit().putInt("comparison_count", count.coerceIn(1, 3)).apply() }
}

private fun Modifier.bodyComparisonDividers(comparisonCount: Int): Modifier = drawBehind {
    val count = comparisonCount.coerceAtLeast(1)
    val gap = 4.dp.toPx()
    val weightWidth = (size.width - gap * count) / (BodyItemColumnWeight + BodyValueColumnWeight * count)
    repeat(count) { index ->
        val x = weightWidth * (BodyItemColumnWeight + BodyValueColumnWeight * index) + gap * (index + 0.5f)
        drawLine(DividerColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
    }
}

private fun bodyMeasurementAscendingComparator(): Comparator<BodyCompositionMeasurement> =
    compareBy<BodyCompositionMeasurement>({ it.targetDate }, { it.measuredTime ?: LocalTime.MIN }, { it.id })

private fun bodyMeasurementDescendingComparator(): Comparator<BodyCompositionMeasurement> =
    bodyMeasurementAscendingComparator().reversed()

private fun bodyMeasurementColumnLabel(
    measurement: BodyCompositionMeasurement,
    comparison: List<BodyCompositionMeasurement>,
): String {
    val sameDate = comparison.filter { it.targetDate == measurement.targetDate }
    val sameMonth = comparison.count {
        it.targetDate.year == measurement.targetDate.year && it.targetDate.monthValue == measurement.targetDate.monthValue
    }
    val month = "${measurement.targetDate.monthValue}月"
    val date = "$month\n${measurement.targetDate.dayOfMonth}日"
    return when {
        sameMonth <= 1 -> month
        sameDate.size <= 1 -> date
        measurement.measuredTime != null -> {
            val sameTime = sameDate.count { it.measuredTime == measurement.measuredTime }
            val occurrence = if (sameTime > 1) "・${sameDate.indexOf(measurement) + 1}回目" else ""
            "$date\n${formatBodyTime(measurement.measuredTime)}$occurrence"
        }
        else -> "$date\n${sameDate.indexOf(measurement) + 1}回目"
    }
}

private fun bodyMeasurementDateLabel(
    measurement: BodyCompositionMeasurement,
    seriesMeasurements: List<BodyCompositionMeasurement>,
): String {
    val sameDateAndTime = seriesMeasurements.filter {
        it.targetDate == measurement.targetDate && it.measuredTime == measurement.measuredTime
    }
    val occurrence = if (sameDateAndTime.size > 1) "・${sameDateAndTime.indexOf(measurement) + 1}件目" else ""
    return "${measurement.targetDate} " +
        (measurement.measuredTime?.let(::formatBodyTime) ?: "時刻不明") + occurrence
}

private fun bodyMeasurementShortDateLabel(measurement: BodyCompositionMeasurement, showTime: Boolean): String =
    measurement.targetDate.format(DateTimeFormatter.ofPattern("yyyy/M/d")) +
        (measurement.measuredTime?.takeIf { showTime }?.let { " ${formatBodyTime(it)}" } ?: "")

private fun bodyMeasurementXPosition(measurement: BodyCompositionMeasurement): Double =
    measurement.targetDate.toEpochDay().toDouble() +
        (measurement.measuredTime?.let { (it.toSecondOfDay().toDouble() + it.nano / 1_000_000_000.0) / 86_400.0 } ?: 0.0)

private fun formatBodyTime(time: LocalTime): String =
    time.format(if (time.second == 0 && time.nano == 0) BodyTimeFormatter else DateTimeFormatter.ofPattern("HH:mm:ss", Locale.JAPAN))

private fun formatBodyValue(value: BodyCompositionValue): String =
    value.value + bodyCompositionDisplayUnit(value.unit).takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()

/** 保存・同期用の単位を維持し、体組成画面では用紙と同じ短い単位を表示する。 */
internal fun bodyCompositionDisplayUnit(unit: String): String = if (unit == "kcal/日") "kcal" else unit

private fun sourceDisplayName(source: String): String = when (source.lowercase()) {
    "photo" -> "写真"
    "manual" -> "手入力"
    "csv" -> "CSV"
    else -> source
}
