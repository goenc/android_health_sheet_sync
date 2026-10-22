package com.goenc.healthsheetsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.goenc.healthsheetsync.data.LabReferenceRange

@Composable
internal fun LabReferenceRangeDialog(
    itemName: String,
    range: LabReferenceRange?,
    unit: String,
    onDismiss: () -> Unit,
    onSave: (LabReferenceRange?, String, (String?) -> Unit) -> Unit,
) {
    var minimum by remember(itemName) { mutableStateOf(range?.minimum?.toPlainString().orEmpty()) }
    var maximum by remember(itemName) { mutableStateOf(range?.maximum?.toPlainString().orEmpty()) }
    var minimumInclusive by remember(itemName) { mutableStateOf(range?.minimumInclusive ?: true) }
    var maximumInclusive by remember(itemName) { mutableStateOf(range?.maximumInclusive ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var unitText by remember(itemName) { mutableStateOf(unit) }
    var saving by remember { mutableStateOf(false) }
    fun save(value: LabReferenceRange?) {
        if (saving) return
        saving = true
        onSave(value, unitText) { failure ->
            saving = false
            if (failure == null) onDismiss() else error = failure
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("$itemName の項目設定") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("この項目の全日付に共通で適用する。今後の取り込みにも使用する。")
            OutlinedTextField(unitText, { unitText = it }, label = { Text("単位（なしは空欄）") }, singleLine = true)
            OutlinedTextField(minimum, { minimum = it }, label = { Text("下限（空欄可）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(minimumInclusive, { minimumInclusive = it }, enabled = minimum.isNotBlank())
                Text("下限と同じ値を正常範囲に含む")
            }
            OutlinedTextField(maximum, { maximum = it }, label = { Text("上限（空欄可）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(maximumInclusive, { maximumInclusive = it }, enabled = maximum.isNotBlank())
                Text("上限と同じ値を正常範囲に含む")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { save(null) }, enabled = !saving) { Text("この項目の高低判定を使わない") }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val candidate = runCatching {
                LabReferenceRange(
                    minimum.trim().takeIf { it.isNotEmpty() }?.toBigDecimal(),
                    maximum.trim().takeIf { it.isNotEmpty() }?.toBigDecimal(),
                    minimumInclusive,
                    maximumInclusive,
                )
            }
            candidate.onSuccess { save(it) }.onFailure {
                error = if (it is NumberFormatException) "下限・上限は数値で入力してください" else it.message
            }
        }, enabled = !saving) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("キャンセル") } })
}
