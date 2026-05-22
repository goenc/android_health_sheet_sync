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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType

@Composable
internal fun SettingsScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onShareCsvToDrive: () -> Unit,
    csvShareStatus: String?,
    onBack: () -> Unit,
) {
    var selectedRecordList by remember { mutableStateOf<RecordListType?>(null) }

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
            RecordListType.Steps -> StepDailySummary(state.stepDailyRecords)
            RecordListType.BloodPressure -> BloodPressureRecordSummary(state.manualRecords)
            RecordListType.Waist -> WaistRecordSummary(state.manualRecords)
            RecordListType.A1c -> A1cRecordSummary(state.a1cDailyRecords)
            null -> {
                Text(
                    text = "表示する記録を選択してください",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppText,
                )
            }
        }
    }
    DebugSection(title = "ヘルスコネクト") {
        DebugLine("利用可否", state.availability.displayText())
        DebugLine("権限", state.permissions.displayText())
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

@Composable
internal fun DebugSection(
    title: String,
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
        HorizontalDivider(color = DividerColor)
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun StepDailySummary(dailySteps: List<DebugStepDaily>) {
    dailySteps
        .sortedByDescending { it.targetDate }
        .forEach { steps ->
            Text(
                text = "${steps.targetDate}  ${steps.steps}歩",
                style = MaterialTheme.typography.bodyMedium,
            )
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
