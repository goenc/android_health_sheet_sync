package com.goenc.healthsheetsync.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.DailyEnergyCalculator
import com.goenc.healthsheetsync.health.DailyEnergySnapshot
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun SettingsScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onShareCsvToDrive: () -> Unit,
    csvShareStatus: String?,
    onRecordListVisibilityChanged: (Boolean) -> Unit,
    basalMetabolicRate: Int,
    onSaveBasalMetabolicRate: (Int, (Boolean) -> Unit) -> Unit,
    dailyEnergySnapshots: List<DailyEnergySnapshot>,
    onBack: () -> Unit,
) {
    var selectedRecordList by remember { mutableStateOf<RecordListType?>(null) }
    LaunchedEffect(selectedRecordList) {
        onRecordListVisibilityChanged(selectedRecordList != null)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "設定",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        OutlinedButton(onClick = onBack) {
            Text("メインに戻る")
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onShareCsvToDrive,
                enabled = !state.isLoading,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppPrimary,
                    contentColor = Color.White,
                ),
                modifier = Modifier.defaultMinSize(minWidth = 128.dp, minHeight = 52.dp),
            ) {
                Text("Drive保存")
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
                shape = CircleShape,
                modifier = Modifier.defaultMinSize(minWidth = 128.dp, minHeight = 52.dp),
            ) {
                Text(if (state.isLoading) "読み込み中" else "データ更新")
            }
        }
        csvShareStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(color = DividerColor)
    }
    DebugSection(title = "記録一覧") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            DebugLineCell("体重記録の件数", state.weightRecords.size.toString())
            DebugLineCell("血糖値記録の件数", state.glucoseRecords.size.toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            DebugLineCell("歩数記録の日数", state.stepDailyRecords.size.toString())
            DebugLineCell("血圧記録の件数", state.manualRecords.count { it.type == ManualRecordType.BloodPressure && it.invalidatedAt == null }.toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            DebugLineCell("腹囲記録の件数", state.manualRecords.count { it.type == ManualRecordType.Waist && it.invalidatedAt == null }.toString())
            DebugLineCell("A1c記録の件数", state.a1cDailyRecords.size.toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordListButton(
                label = "体重",
                selected = selectedRecordList == RecordListType.Weight,
                onClick = {
                    selectedRecordList =
                        if (selectedRecordList == RecordListType.Weight) null else RecordListType.Weight
                },
            )
            RecordListButton(
                label = "血糖値",
                selected = selectedRecordList == RecordListType.Glucose,
                onClick = {
                    selectedRecordList =
                        if (selectedRecordList == RecordListType.Glucose) null else RecordListType.Glucose
                },
            )
            RecordListButton(
                label = "歩数",
                selected = selectedRecordList == RecordListType.Steps,
                onClick = {
                    selectedRecordList =
                        if (selectedRecordList == RecordListType.Steps) null else RecordListType.Steps
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordListButton(
                label = "血圧",
                selected = selectedRecordList == RecordListType.BloodPressure,
                onClick = {
                    selectedRecordList =
                        if (selectedRecordList == RecordListType.BloodPressure) null else RecordListType.BloodPressure
                },
            )
            RecordListButton(
                label = "腹囲",
                selected = selectedRecordList == RecordListType.Waist,
                onClick = {
                    selectedRecordList =
                        if (selectedRecordList == RecordListType.Waist) null else RecordListType.Waist
                },
            )
            RecordListButton(
                label = "A1c",
                selected = selectedRecordList == RecordListType.A1c,
                onClick = {
                    selectedRecordList =
                        if (selectedRecordList == RecordListType.A1c) null else RecordListType.A1c
                },
            )
        }
        when (selectedRecordList) {
            RecordListType.Weight -> WeightDailySummary(state.weightRecords)
            RecordListType.Glucose -> GlucoseRecordSummary(state.glucoseRecords)
            RecordListType.Steps -> StepDailySummary(
                dailySteps = state.stepDailyRecords,
                basalMetabolicRate = basalMetabolicRate,
                onSaveBasalMetabolicRate = onSaveBasalMetabolicRate,
                dailyEnergySnapshots = dailyEnergySnapshots,
            )
            RecordListType.BloodPressure -> BloodPressureRecordSummary(state.manualRecords)
            RecordListType.Waist -> WaistRecordSummary(state.manualRecords)
            RecordListType.A1c -> A1cRecordSummary(state.a1cDailyRecords)
            null -> Unit
        }
    }
    DebugSection(title = "ヘルスコネクト", showDivider = false) {
        DebugLine("利用可否", state.availability.displayText())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DebugLine(
                label = "権限",
                value = state.permissions.displayText(),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    Log.d(TAG, "Permission request button clicked; invoking onRequestPermissions.")
                    onRequestPermissions()
                },
                enabled = state.canRequestPermissions && !state.isLoading,
                shape = CircleShape,
            ) {
                Text("権限をリクエスト")
            }
        }
    }
}

@Composable
internal fun DebugSection(
    title: String,
    showDivider: Boolean = true,
    content: @Composable ColumnScopeMarker.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = AppText,
        )
        ColumnScopeMarker.content()
        if (showDivider) {
            HorizontalDivider(color = DividerColor)
        }
    }
}

internal object ColumnScopeMarker

private enum class RecordListType {
    Weight,
    Glucose,
    Steps,
    BloodPressure,
    Waist,
    A1c,
}

@Composable
private fun RecordListButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppPrimary,
                contentColor = Color.White,
            ),
            modifier = Modifier.defaultMinSize(minWidth = 92.dp, minHeight = 44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText),
            modifier = Modifier.defaultMinSize(minWidth = 92.dp, minHeight = 44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(label)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugLine(label: String, value: String) {
    DebugLine(label = label, value = value, modifier = Modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugLine(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AppMutedBlue,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = AppText,
        )
    }
}

@Composable
private fun RowScope.DebugLineCell(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        DebugLine(label, value)
    }
}

@Composable
private fun WeightDailySummary(records: List<DebugWeightRecord>) {
    records
        .groupBy { it.targetDate }
        .toSortedMap(compareByDescending { it })
        .forEach { (date, dailyRecords) ->
            Text(
                text = "$date  朝 ${dailyRecords.weightTextFor("朝")}、夜 ${dailyRecords.weightTextFor("夜")}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
}

@Composable
private fun StepDailySummary(
    dailySteps: List<DebugStepDaily>,
    basalMetabolicRate: Int,
    onSaveBasalMetabolicRate: (Int, (Boolean) -> Unit) -> Unit,
    dailyEnergySnapshots: List<DailyEnergySnapshot>,
) {
    var inputValue by remember(basalMetabolicRate) { mutableStateOf(basalMetabolicRate.toString()) }
    var inputError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "現在の基礎代謝：${formatIntegerWithGrouping(basalMetabolicRate)} kcal/日",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = inputValue,
            onValueChange = {
                inputValue = it
                inputError = null
            },
            label = { Text("基礎代謝量（kcal/日）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = inputError != null,
            modifier = Modifier.fillMaxWidth(),
        )
        inputError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = {
                val value = inputValue.trim().toIntOrNull()
                if (value == null) {
                    inputError = "基礎代謝量は整数で入力してください"
                } else if (value <= 0) {
                    inputError = "基礎代謝量は0より大きい整数で入力してください"
                } else {
                    onSaveBasalMetabolicRate(value) { success ->
                        inputError = if (success) {
                            null
                        } else {
                            "基礎代謝量を保存できませんでした"
                        }
                    }
                }
            },
            shape = CircleShape,
        ) {
            Text("保存")
        }
    }

    if (dailySteps.isEmpty()) {
        Text("歩数記録はありません", style = MaterialTheme.typography.bodyMedium)
        return
    }

    dailySteps
        .sortedByDescending { it.targetDate }
        .forEach { steps ->
            val energy = DailyEnergyCalculator.resolveDisplay(
                targetDate = steps.targetDate,
                today = LocalDate.now(ZoneId.systemDefault()),
                currentSteps = steps.steps,
                currentBasalMetabolicRate = basalMetabolicRate,
                snapshot = dailyEnergySnapshots.firstOrNull { it.targetDate == steps.targetDate },
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = steps.targetDate.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("${formatIntegerWithGrouping(energy?.steps ?: steps.steps)}歩")
                Text("基礎代謝 ${energy?.basalMetabolicRate?.let(::formatIntegerWithGrouping) ?: "-"} kcal/日")
                Text("PAL ${energy?.pal?.let(::formatPal) ?: "-"}")
                Text("推定総消費 ${energy?.estimatedTotalKcal?.let(::formatIntegerWithGrouping) ?: "-"} kcal/日")
            }
        }
}

@Composable
private fun GlucoseRecordSummary(records: List<DebugGlucoseRecord>) {
    if (records.isEmpty()) {
        Text(
            text = "血糖値記録はありません",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    records
        .sortedByDescending { it.measuredAt }
        .forEach { record ->
            Text(
                text = "${record.measuredAt.formatDateTime()}  ${formatDecimal(record.bloodGlucoseMgDl)} mg/dL  ${record.mealRelation}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
}

@Composable
private fun GlucoseRecordRow(record: DebugGlucoseRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${record.measuredAt.formatDateTime()} / ${record.timeBand}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("${formatDecimal(record.bloodGlucoseMgDl)} mg/dL / ${record.mealRelation}")
        Text("対象日 ${record.targetDate}")
        Text("識別子 ${record.healthConnectId}")
        Text("取得元 ${record.sourceAppName} / ${record.sourcePackageName}")
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun BloodPressureRecordSummary(records: List<ManualHealthRecord>) {
    val validRecords = records
        .filter { it.type == ManualRecordType.BloodPressure && it.invalidatedAt == null }
        .sortedByDescending { it.measuredAt }
    if (validRecords.isEmpty()) {
        Text("血圧記録はありません", style = MaterialTheme.typography.bodyMedium)
        return
    }
    validRecords.forEach { record ->
        Text(
            text = "${record.measuredAt.formatDateTime()}  ${record.valueText}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WaistRecordSummary(records: List<ManualHealthRecord>) {
    val validRecords = records
        .filter { it.type == ManualRecordType.Waist && it.invalidatedAt == null }
        .sortedByDescending { it.measuredAt }
    if (validRecords.isEmpty()) {
        Text("腹囲記録はありません", style = MaterialTheme.typography.bodyMedium)
        return
    }
    validRecords.forEach { record ->
        Text(
            text = "${record.measuredAt.formatDateTime()}  ${record.valueText}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun A1cRecordSummary(records: List<DebugA1cDaily>) {
    if (records.isEmpty()) {
        Text("A1c記録はありません", style = MaterialTheme.typography.bodyMedium)
        return
    }
    records
        .sortedByDescending { it.measuredAt }
        .forEach { record ->
            Text(
                text = "${record.measuredAt.formatDateTime()}  ${formatDecimal(record.a1cPercent)}%",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
}
