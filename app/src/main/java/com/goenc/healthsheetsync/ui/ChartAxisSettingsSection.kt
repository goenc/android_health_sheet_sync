package com.goenc.healthsheetsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goenc.healthsheetsync.data.ChartAxisRange
import com.goenc.healthsheetsync.data.ChartMetric

@Composable
internal fun ChartAxisSettingsSection(ranges: Map<ChartMetric, ChartAxisRange>, onSave: (ChartMetric, ChartAxisRange?) -> Boolean) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(if (expanded) "グラフの表示範囲を閉じる" else "グラフの表示範囲")
    }
    if (!expanded) return
    Text("項目ごとに最小値・最大値を保存。「自動」で元の表示範囲に戻る。", style = MaterialTheme.typography.bodySmall)
    ChartMetric.entries.forEach { metric ->
        val range = ranges[metric]
        var minimum by remember(range) { mutableStateOf(range?.minimum?.toString().orEmpty()) }
        var maximum by remember(range) { mutableStateOf(range?.maximum?.toString().orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        var saved by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(metric.label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = minimum, onValueChange = { minimum = it; error = null; saved = false },
                    label = { Text("最小値") }, singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(value = maximum, onValueChange = { maximum = it; error = null; saved = false },
                    label = { Text("最大値") }, singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val parsed = ChartAxisRange.parse(minimum, maximum)
                    if (parsed == null) error = "0以上の数値で、最大値を最小値より大きくしてください。"
                    else if (onSave(metric, parsed)) { error = null; saved = true }
                    else error = "表示範囲を保存できませんでした。"
                }) { Text("保存") }
                OutlinedButton(onClick = {
                    if (onSave(metric, null)) { minimum = ""; maximum = ""; error = null; saved = true }
                    else error = "表示範囲を保存できませんでした。"
                }) { Text("自動") }
                if (saved) Text("保存済み")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
