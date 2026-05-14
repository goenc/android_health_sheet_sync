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
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthDebugUiState

@Composable
internal fun SettingsScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onSaveExternalWorkbook: () -> Unit,
    externalSaveStatus: String?,
    onShareCsvToDrive: () -> Unit,
    csvShareStatus: String?,
    targetSpreadsheetUrl: String,
    onUploadSpreadsheet: () -> Unit,
    spreadsheetUploadStatus: String?,
    canUploadSpreadsheet: Boolean,
    isSpreadsheetUploading: Boolean,
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
    DebugSection(title = "操作") {
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
            Button(
                onClick = onUploadSpreadsheet,
                enabled = canUploadSpreadsheet,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppPrimary,
                    contentColor = Color.White,
                ),
                modifier = Modifier.defaultMinSize(minWidth = 148.dp, minHeight = 52.dp),
            ) {
                Text(if (isSpreadsheetUploading) "送信中" else "アップロード")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
            OutlinedButton(
                onClick = onSaveExternalWorkbook,
                shape = CircleShape,
            ) {
                Text("外部保存")
            }
        }
        spreadsheetUploadStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    externalSaveStatus?.let { status ->
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    DebugSection(title = "記録一覧") {
        DebugLine("体重記録の件数", state.weightRecords.size.toString())
        DebugLine("血糖値記録の件数", state.glucoseRecords.size.toString())
        DebugLine("歩数記録の日数", state.stepDailyRecords.size.toString())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordListButton(
                label = "体重",
                selected = selectedRecordList == RecordListType.Weight,
                onClick = { selectedRecordList = RecordListType.Weight },
            )
            RecordListButton(
                label = "血糖値",
                selected = selectedRecordList == RecordListType.Glucose,
                onClick = { selectedRecordList = RecordListType.Glucose },
            )
            RecordListButton(
                label = "歩数",
                selected = selectedRecordList == RecordListType.Steps,
                onClick = { selectedRecordList = RecordListType.Steps },
            )
        }
        when (selectedRecordList) {
            RecordListType.Weight -> WeightDailySummary(state.weightRecords)
            RecordListType.Glucose -> GlucoseRecordSummary(state.glucoseRecords)
            RecordListType.Steps -> StepDailySummary(state.stepDailyRecords)
            null -> {
                Text(
                    text = "表示する記録を選択してください",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppText,
                )
            }
        }
    }
    DebugSection(title = "アップロード先") {
        DebugLine("対象スプレッドシート", targetSpreadsheetUrl)
        DebugLine("Drive保存方式", "Android共有でCSV保存")
        DebugLine("方式", "Googleログインで直接書き込み")
    }
    DebugSection(title = "ヘルスコネクト") {
        DebugLine("利用可否", state.availability.displayText())
        DebugLine("権限", state.permissions.displayText())
    }
    DebugSection(title = "デバッグ") {
        state.sourceSummaries.forEach { source ->
            DebugLine("取得元", source)
        }
        state.debugMessages.forEach { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
