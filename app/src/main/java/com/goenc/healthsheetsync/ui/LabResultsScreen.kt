package com.goenc.healthsheetsync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
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

@Composable
fun LabResultsScreen(
    state: LabResultsUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var selectedItem by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedUnit by rememberSaveable(selectedItem) { mutableStateOf<String?>(null) }
    var selectedPlotRecord by remember(selectedItem) { mutableStateOf<LabResult?>(null) }
    val dates = remember(state.records) {
        state.records.map { it.targetDate }.distinct().sortedDescending()
    }
    val effectiveDate = selectedDate?.let { date -> dates.firstOrNull { it.toString() == date } } ?: dates.firstOrNull()
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
    BackHandler(enabled = showSettings || selectedItem != null) {
        if (showSettings) showSettings = false else selectedItem = null
    }
    if (showSettings) {
        LabResultsSettingsScreen(state = state, onRefresh = onRefresh, onBack = { showSettings = false }, modifier = modifier)
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
                    TextButton(onClick = { selectedItem = null }) { Text("日付別へ") }
                }
                if (units.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        units.forEach { unit ->
                            FilterChip(selected = unit == effectiveUnit, onClick = { selectedUnit = unit; selectedPlotRecord = null },
                                label = { Text(unit.ifBlank { "単位なし" }) })
                        }
                    }
                } else if (!effectiveUnit.isNullOrBlank()) {
                    Text(effectiveUnit, style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
                }
                LabResultChart(records = detailRecords, selectedRecord = selectedPlotRecord, onSelectRecord = { selectedPlotRecord = it })
            } else {
                val dateIndex = dates.indexOf(effectiveDate)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedDate = dates[dateIndex - 1].toString() }, enabled = dateIndex > 0) { Text("前へ") }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(effectiveDate.toString(), fontWeight = FontWeight.Bold)
                        Text("${dateIndex + 1} / ${dates.size}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { selectedDate = dates[dateIndex + 1].toString() }, enabled = dateIndex < dates.lastIndex) { Text("次へ") }
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
                            val dateIndex = dates.indexOf(effectiveDate)
                            val nextIndex = when {
                                dragDistance <= -threshold -> dateIndex + 1
                                dragDistance >= threshold -> dateIndex - 1
                                else -> dateIndex
                            }
                            if (nextIndex in dates.indices) selectedDate = dates[nextIndex].toString()
                        },
                        onHorizontalDrag = { _, amount -> dragDistance += amount },
                    )
                }
            }) {
                if (detailRecords.isEmpty()) item { Text("該当する項目はありません") }
                items(detailRecords) { result ->
                    LabResultRow(result, showDate = selectedItem != null, selected = result == selectedPlotRecord,
                        onClick = { if (selectedItem == null) selectedItem = result.itemName else selectedPlotRecord = result })
                }
            }
        }
    }
}

@Composable
private fun LabResultsSettingsScreen(state: LabResultsUiState, onRefresh: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("戻る") }
            Text("検査結果の設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onRefresh, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.isLoading) "更新中" else "更新")
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
            },
                color = if (result.flag.isNotEmpty()) MaterialTheme.colorScheme.error else AppText,
                fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.End,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.1f))
        }
        HorizontalDivider(color = DividerColor)
    }
}
