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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.goenc.healthsheetsync.data.LabResult
import com.goenc.healthsheetsync.data.LabResultsUiState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LabResultsScreen(
    state: LabResultsUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var selectedItem by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val dates = remember(state.records, newestFirst) {
        state.records.map { it.targetDate }.distinct().let { if (newestFirst) it.sortedDescending() else it.sorted() }
    }
    val effectiveDate = selectedDate?.let { date -> dates.firstOrNull { it.toString() == date } } ?: dates.firstOrNull()
    val visible = remember(state.records, query, selectedItem, effectiveDate, newestFirst) {
        state.records.filter {
            (selectedItem == null || it.itemName == selectedItem) &&
                (selectedItem != null || it.targetDate == effectiveDate) &&
                it.itemName.contains(query, ignoreCase = true)
        }.let { records -> if (newestFirst) records.sortedByDescending { it.targetDate } else records.sortedBy { it.targetDate } }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("戻る") }
            Text("検査結果", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) { Text("更新") }
        }
        if (state.isLoading) CircularProgressIndicator()
        state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.lastUpdatedAt?.let {
            Text("取得 ${it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))}",
                style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        }
        if (state.records.isEmpty()) {
            Text("検査結果はまだありません。更新でGoogle Sheetsから取得する。")
            Text("ChatGPTで過去の検査結果を追加すると、ここで日付・項目名・値を確認できる。", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = newestFirst, onClick = { newestFirst = !newestFirst }, label = { Text(if (newestFirst) "新しい日順" else "古い日順") })
                Text("${dates.size}日 / ${state.records.size}項目", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("項目名で検索") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                Text("項目をタップすると、全日付の履歴を表示", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visible.isEmpty()) item { Text("該当する項目はありません") }
                items(visible) { result ->
                    LabResultRow(result, showDate = selectedItem != null, onClick = { selectedItem = result.itemName; query = "" })
                }
            }
        }
    }
}

@Composable
private fun LabResultRow(result: LabResult, showDate: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showDate) Text(result.targetDate.toString(), style = MaterialTheme.typography.labelLarge, color = AppMutedBlue)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(result.itemName, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(listOf(result.value, result.unit).filter { it.isNotBlank() }.joinToString(" "),
                color = if (result.flag.isNotEmpty()) MaterialTheme.colorScheme.error else AppText,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
        }
        if (result.referenceRange.isNotBlank()) Text("基準値 ${result.referenceRange}", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
        if (result.flag.isNotBlank()) Text(if (result.flag == "H") "↑ 検査票で高値" else "↓ 検査票で低値", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        HorizontalDivider(color = DividerColor)
    }
}
