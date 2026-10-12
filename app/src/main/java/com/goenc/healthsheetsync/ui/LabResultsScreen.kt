package com.goenc.healthsheetsync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.R
import com.goenc.healthsheetsync.data.LabResult
import com.goenc.healthsheetsync.data.LabResultsUiState
import com.goenc.healthsheetsync.data.LabDisplaySettingsStore

private const val LabComparisonCount = 3
private const val LabItemColumnWeight = 2.4f
private const val LabValueColumnWeight = 1f

private fun Modifier.labMonthDividers(): Modifier = drawBehind {
    val gap = 4.dp.toPx()
    val weightWidth = (size.width - gap * LabComparisonCount) /
        (LabItemColumnWeight + LabValueColumnWeight * LabComparisonCount)
    repeat(LabComparisonCount) { index ->
        val x = weightWidth * (LabItemColumnWeight + LabValueColumnWeight * index) + gap * (index + 0.5f)
        drawLine(DividerColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
    }
}

@Composable
fun LabResultsScreen(
    state: LabResultsUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displaySettings = remember(context) { LabDisplaySettingsStore(context.applicationContext) }
    var excludedItems by remember { mutableStateOf(displaySettings.excludedItems()) }
    var showTargetOnly by remember { mutableStateOf(displaySettings.showTargetOnly()) }
    val itemNames = remember(state.records) {
        state.records.sortedBy { it.targetDate }.map { it.itemName }.distinct()
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var selectedItem by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedUnit by rememberSaveable(selectedItem) { mutableStateOf<String?>(null) }
    var selectedPlotRecord by remember(selectedItem) { mutableStateOf<LabResult?>(null) }
    val dates = remember(state.records) {
        state.records.map { it.targetDate }.distinct().sortedDescending()
    }
    val maxDateIndex = (dates.size - LabComparisonCount).coerceAtLeast(0)
    val dateIndex = dates.indexOfFirst { it.toString() == selectedDate }.coerceIn(0, maxDateIndex)
    val effectiveDate = dates.getOrNull(dateIndex)
    val comparisonDates = dates.drop(dateIndex).take(LabComparisonCount)
    val comparisonRows = remember(state.records, comparisonDates) {
        comparisonDates.map { dailyLabResultRows(state.records, it) }
    }
    val visible = remember(state.records, selectedItem, effectiveDate) {
        if (selectedItem == null) {
            effectiveDate?.let { dailyLabResultRows(state.records, it) }.orEmpty()
        } else {
            state.records.filter { it.itemName == selectedItem }.sortedByDescending { it.targetDate }
        }
    }
    val units = visible.map { it.unit }.distinct()
    val effectiveUnit = selectedUnit?.takeIf { it in units } ?: units.firstOrNull()
    val detailRecords = if (selectedItem != null) visible.filter { it.unit == effectiveUnit } else visible
    val comparisonIndices = visible.indices.filter { !showTargetOnly || visible[it].itemName !in excludedItems }
    BackHandler(enabled = showSettings || selectedItem != null) {
        if (showSettings) showSettings = false else selectedItem = null
    }
    if (showSettings) {
        LabResultsSettingsScreen(state = state, itemNames = itemNames, excludedItems = excludedItems,
            onItemChecked = { name, checked ->
                excludedItems = if (checked) excludedItems - name else excludedItems + name
                displaySettings.saveExcludedItems(excludedItems)
            }, onRefresh = onRefresh, onBack = { showSettings = false }, modifier = modifier)
        return
    }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (selectedItem != null) selectedItem = null else onBack() }) { Text("戻る") }
            Text("検査結果", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showSettings = true }) {
                Icon(painterResource(R.drawable.ic_settings_24), contentDescription = "検査結果の設定")
            }
        }
        if (state.isLoading) CircularProgressIndicator()
        // 通常の取得状況は設定へ移し、対処が必要なエラーだけをここに残す。
        state.status?.takeIf { !state.isLoading && it != "${state.records.size}件を取得" }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.records.isEmpty()) {
            Text("検査結果はまだありません。設定の「更新」でGoogle Sheetsから取得する。")
            Text("ChatGPTで過去の検査結果を追加すると、ここで日付・項目名・値を確認できる。", style = MaterialTheme.typography.bodyMedium)
        } else {
            if (selectedItem != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("$selectedItem の履歴", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    selectedItem?.let { LabItemGuidancePanel(it, effectiveUnit.orEmpty()) }
                }
                if (units.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        units.forEach { unit ->
                            FilterChip(selected = unit == effectiveUnit, onClick = { selectedUnit = unit; selectedPlotRecord = null },
                                label = { Text(unit.ifBlank { "単位なし" }) })
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedDate = dates[dateIndex - 1].toString() }, enabled = dateIndex > 0) { Text("前へ") }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${comparisonDates.first().year}年", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { selectedDate = dates[dateIndex + 1].toString() }, enabled = dateIndex < maxDateIndex) { Text("次へ") }
                }
                Row(Modifier.fillMaxWidth().labMonthDividers(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(LabItemColumnWeight), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = !showTargetOnly, onClick = {
                            showTargetOnly = false
                            displaySettings.saveShowTargetOnly(false)
                        }, label = { Text("全項目", fontSize = 10.sp, maxLines = 1) })
                        FilterChip(selected = showTargetOnly, onClick = {
                            showTargetOnly = true
                            displaySettings.saveShowTargetOnly(true)
                        }, label = { Text("対象項目", fontSize = 10.sp, maxLines = 1) })
                    }
                    comparisonDates.forEach { date ->
                        Text("${date.monthValue}月", modifier = Modifier.weight(LabValueColumnWeight).padding(end = 4.dp), textAlign = TextAlign.End,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    repeat(LabComparisonCount - comparisonDates.size) { Text("", modifier = Modifier.weight(LabValueColumnWeight)) }
                }
            }
            LazyColumn(modifier = Modifier.weight(1f).let { listModifier ->
                if (selectedItem != null) listModifier else listModifier.pointerInput(dates, effectiveDate) {
                    var dragDistance = 0f
                    val threshold = 48.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onDragCancel = { dragDistance = 0f },
                        onDragEnd = {
                            val nextIndex = when {
                                dragDistance <= -threshold -> (dateIndex + 1).coerceAtMost(maxDateIndex)
                                dragDistance >= threshold -> (dateIndex - 1).coerceAtLeast(0)
                                else -> dateIndex
                            }
                            if (nextIndex in dates.indices) selectedDate = dates[nextIndex].toString()
                        },
                        onHorizontalDrag = { _, amount -> dragDistance += amount },
                    )
                }
            }) {
                if (detailRecords.isEmpty()) item { Text("該当する項目はありません") }
                if (selectedItem == null) {
                    if (comparisonIndices.isEmpty()) item { Text("対象項目がありません。設定で表示する項目を選択してください。") }
                    items(comparisonIndices) { index ->
                        LabComparisonRow(comparisonRows.map { it[index] }, onClick = { selectedItem = visible[index].itemName })
                    }
                } else {
                    item {
                        LabResultChart(records = detailRecords, selectedRecord = selectedPlotRecord, onSelectRecord = { selectedPlotRecord = it })
                    }
                    items(detailRecords) { result ->
                        LabResultRow(result, showDate = true, selected = result == selectedPlotRecord,
                            onClick = { selectedPlotRecord = result })
                    }
                }
            }
        }
    }
}

@Composable
private fun LabComparisonRow(results: List<LabResult>, onClick: () -> Unit) {
    val item = results.first()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().labMonthDividers().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.itemName, modifier = Modifier.weight(LabItemColumnWeight), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            results.forEach { result ->
                Text(when (result.flag) { "H" -> "↑ "; "L" -> "↓ "; else -> "" } + result.value,
                    modifier = Modifier.weight(LabValueColumnWeight).padding(end = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when (result.flag) { "L" -> ChartBlue; "H" -> MaterialTheme.colorScheme.error; else -> AppText })
            }
            repeat(LabComparisonCount - results.size) { Text("", modifier = Modifier.weight(LabValueColumnWeight)) }
        }
        HorizontalDivider(color = DividerColor)
    }
}

@Composable
private fun LabResultsSettingsScreen(
    state: LabResultsUiState,
    itemNames: List<String>,
    excludedItems: Set<String>,
    onItemChecked: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("戻る") }
            Text("検査結果の設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onRefresh, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.isLoading) "更新中" else "更新")
        }
        Text("対象項目", fontWeight = FontWeight.Bold)
        Text("チェックした項目を「対象項目」で表示する。", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            if (itemNames.isEmpty()) item { Text("更新すると検査項目が表示されます。") }
            items(itemNames, key = { it }) { name ->
                val checked = name !in excludedItems
                Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                    onValueChange = { onItemChecked(name, it) }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                }
                HorizontalDivider(color = DividerColor)
            }
        }
    }
}

@Composable
private fun LabResultRow(result: LabResult, showDate: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(if (selected) AppPrimary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                append(if (showDate) result.targetDate.toString() else result.itemName)
                if (!showDate && result.unit.isNotBlank()) withStyle(SpanStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal)) {
                    append(" (${result.unit})")
                }
            },
                modifier = Modifier.weight(1.35f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showDate) Text(result.referenceRange, modifier = Modifier.weight(0.9f), fontSize = 10.sp,
                color = AppMutedBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(buildAnnotatedString {
                append(when (result.flag) { "H" -> "↑ "; "L" -> "↓ "; else -> "" })
                append(result.value)
                if (result.unit.isNotBlank()) withStyle(SpanStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal)) {
                    append(" ${result.unit}")
                }
            },
                color = when (result.flag) { "L" -> ChartBlue; "H" -> MaterialTheme.colorScheme.error; else -> AppText },
                fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.End,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.1f))
        }
        HorizontalDivider(color = DividerColor)
    }
}
