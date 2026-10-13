package com.goenc.healthsheetsync.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.goenc.healthsheetsync.data.LabResult
import com.goenc.healthsheetsync.data.LabResultsUiState
import com.goenc.healthsheetsync.data.parseLabResults
import com.goenc.healthsheetsync.data.parseLabResultsImport
import com.goenc.healthsheetsync.data.planLabResultsImport

@Composable
internal fun LabResultsImportScreen(
    state: LabResultsUiState,
    onImport: (List<LabResult>, (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<LabResult>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    val busy = saving || state.isLoading
    val goBack = { if (!busy) { if (preview != null) preview = null else onBack() } }
    BackHandler { goBack() }
    Column(modifier.fillMaxSize().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            TextButton(onClick = goBack, enabled = !busy) { Text("戻る") }
            Text("検査結果の取り込み", style = MaterialTheme.typography.titleLarge)
        }
        val records = preview
        if (records == null) {
            Text("ChatGPTが出力した検査結果を貼り付ける。保存前に内容を確認できる。")
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                error = null
            }, enabled = !busy) { Text("クリップボードから貼り付け") }
            OutlinedTextField(value = text, onValueChange = { text = it; error = null },
                label = { Text("解析結果") }, modifier = Modifier.fillMaxWidth().weight(1f), enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                runCatching { parseLabResultsImport(text) }
                    .onSuccess { preview = it; error = null }
                    .onFailure { error = it.message ?: "取り込み形式を確認してください" }
            }, enabled = !busy && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("内容を確認") }
        } else {
            val planResult = runCatching { planLabResultsImport(records, state.records) }
            val plan = planResult.getOrNull()
            Text("項目を押すと修正できる。不要な項目は修正画面で除外する。")
            Text("追加 ${plan?.additions?.size ?: 0}件 ／ 同一結果 ${plan?.duplicates?.size ?: 0}件 ／ 相違 ${plan?.conflicts?.size ?: 0}件")
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(records) { index, record ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = !busy) { editingIndex = index }.padding(vertical = 8.dp)) {
                        Text("${record.targetDate}  ${record.itemName}")
                        Text(record.value)
                        plan?.conflicts?.firstOrNull { it.incoming == record }?.let { conflict ->
                            Text(conflict.reason + "。修正または除外が必要。", color = MaterialTheme.colorScheme.error)
                            conflict.existing.forEach { current ->
                                Text("保存済み: ${current.value}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            (error ?: planResult.exceptionOrNull()?.message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) CircularProgressIndicator()
            Button(onClick = {
                saving = true
                error = null
                onImport(records) { failure ->
                    saving = false
                    if (failure == null) onBack() else error = failure
                }
            }, enabled = !busy && plan != null && plan.additions.isNotEmpty() && plan.conflicts.isEmpty(),
                modifier = Modifier.fillMaxWidth()) { Text(if (busy) "保存中" else "確認した結果を保存") }
        }
    }
    editingIndex?.let { index ->
        preview?.getOrNull(index)?.let { record ->
            EditImportedLabResult(record, onDismiss = { editingIndex = null }, onRemove = {
                preview = preview?.filterIndexed { position, _ -> position != index }
                editingIndex = null
            }, onSave = { changed ->
                preview = preview?.mapIndexed { position, item -> if (position == index) changed else item }
                editingIndex = null
            })
        }
    }
}

@Composable
private fun EditImportedLabResult(record: LabResult, onDismiss: () -> Unit, onRemove: () -> Unit, onSave: (LabResult) -> Unit) {
    var fields by remember(record) { mutableStateOf(listOf(record.targetDate.toString(), record.itemName, record.value)) }
    var error by remember { mutableStateOf<String?>(null) }
    val labels = listOf("検査日（YYYY-MM-DD）", "項目名", "値")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("検査結果を修正") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            fields.forEachIndexed { index, value ->
                OutlinedTextField(value, { updated -> fields = fields.mapIndexed { i, old -> if (i == index) updated else old } },
                    label = { Text(labels[index]) }, singleLine = true)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onRemove) { Text("この項目を除外") }
        }
    }, confirmButton = {
        TextButton(onClick = {
            runCatching { parseLabResults(listOf(listOf("targetDate", "itemName", "value"), fields)).single() }
                .onSuccess(onSave).onFailure { error = it.message }
        }) { Text("修正を反映") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}
