package com.goenc.healthsheetsync.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.data.LabResult
import com.goenc.healthsheetsync.data.LabResultsUiState

@Composable
fun LabResultsScreen(
    state: LabResultsUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var selectedItem by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val dates = remember(state.records, newestFirst) {
        state.records.map { it.targetDate }.distinct().let { if (newestFirst) it.sortedDescending() else it.sorted() }
    }
    val effectiveDate = selectedDate?.let { date -> dates.firstOrNull { it.toString() == date } } ?: dates.firstOrNull()
    val visible = remember(state.records, selectedItem, effectiveDate, newestFirst) {
        state.records.filter {
            (selectedItem == null || it.itemName == selectedItem) &&
                (selectedItem != null || it.targetDate == effectiveDate)
        }.let { records -> if (newestFirst) records.sortedByDescending { it.targetDate } else records.sortedBy { it.targetDate } }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("戻る") }
            Text("検査結果", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) { Text("更新") }
        }
        if (state.isLoading) CircularProgressIndicator()
        // 通常の取得状況は設定へ移し、対処が必要なエラーだけをここに残す。
        state.status?.takeIf { !state.isLoading && it != "${state.records.size}件を取得" }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.records.isEmpty()) {
            Text("検査結果はまだありません。更新でGoogle Sheetsから取得する。")
            Text("ChatGPTで過去の検査結果を追加すると、ここで日付・項目名・値を確認できる。", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = newestFirst, onClick = { newestFirst = !newestFirst }, label = { Text(if (newestFirst) "新しい日順" else "古い日順") })
            }
            if (selectedItem != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("$selectedItem の履歴", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { selectedItem = null }) { Text("日付別へ") }
                }
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
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (visible.isEmpty()) item { Text("該当する項目はありません") }
                items(visible) { result ->
                    LabResultRow(result, showDate = selectedItem != null, onClick = { selectedItem = result.itemName })
                }
            }
        }
    }
}

@Composable
private fun LabResultRow(result: LabResult, showDate: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (showDate) result.targetDate.toString() else result.itemName,
                modifier = Modifier.weight(1.35f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(result.referenceRange, modifier = Modifier.weight(0.9f), fontSize = 10.sp,
                color = AppMutedBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(buildAnnotatedString {
                append(when (result.flag) { "H" -> "↑ "; "L" -> "↓ "; else -> "" })
                append(result.value)
                if (result.unit.isNotBlank()) withStyle(SpanStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal)) {
                    append(" ${result.unit}")
                }
            },
                color = if (result.flag.isNotEmpty()) MaterialTheme.colorScheme.error else AppText,
                fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.End,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.1f))
        }
        HorizontalDivider(color = DividerColor)
    }
}
