package com.goenc.healthsheetsync.ui

import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthConnectAvailability
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.InvalidatedGraphRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.PermissionState
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun HealthDebugScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onSaveExternalWorkbook: () -> Unit,
    externalSaveStatus: String?,
    onUploadSpreadsheet: () -> Unit,
    spreadsheetUploadStatus: String?,
    isSpreadsheetUploading: Boolean,
    targetSpreadsheetUrl: String,
    sharedText: String?,
    sharedTextImportStatus: String?,
    onSaveManualRecord: (ManualHealthRecordDraft) -> Unit,
    onInvalidateManualRecord: (String) -> Unit,
    onRestoreManualRecord: (String) -> Unit,
    onDeleteManualRecord: (String) -> Unit,
    onInvalidateStoredRecord: (String, String) -> Unit,
    onRestoreStoredRecord: (String, String) -> Unit,
    onDeleteStoredRecord: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }

    if (state.isLoading && state.availability == HealthConnectAvailability.Checking) {
        LoadingScreen(modifier = modifier)
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (showManualInput) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ManualDataScreen(
                        weightRecords = state.weightRecords,
                        dailySteps = state.stepDailyRecords,
                        glucoseRecords = state.glucoseRecords,
                        manualRecords = state.manualRecords,
                        invalidatedRecords = state.invalidatedGraphRecords,
                        onSave = onSaveManualRecord,
                        onInvalidateManual = onInvalidateManualRecord,
                        onRestoreManual = onRestoreManualRecord,
                        onDeleteManual = onDeleteManualRecord,
                        onInvalidate = onInvalidateStoredRecord,
                        onRestore = onRestoreStoredRecord,
                        onDelete = onDeleteStoredRecord,
                        onBack = {
                            showManualInput = false
                            onRefresh()
                        },
                    )
                }
                return@Column
            }

            if (showSettings) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SettingsScreen(
                        state = state,
                        onRequestPermissions = onRequestPermissions,
                        onRefresh = onRefresh,
                        onSaveExternalWorkbook = onSaveExternalWorkbook,
                        externalSaveStatus = externalSaveStatus,
                        targetSpreadsheetUrl = targetSpreadsheetUrl,
                        onUploadSpreadsheet = onUploadSpreadsheet,
                        spreadsheetUploadStatus = spreadsheetUploadStatus,
                        canUploadSpreadsheet = !state.isLoading && !isSpreadsheetUploading,
                        isSpreadsheetUploading = isSpreadsheetUploading,
                        onBack = { showSettings = false },
                    )
                }
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, top = 4.dp, end = 22.dp, bottom = 22.dp),
            ) {
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    SettingsGearIcon()
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MainSummaryValues(
                        records = state.weightRecords,
                        dailySteps = state.stepDailyRecords,
                        glucoseRecords = state.glucoseRecords,
                        a1cDailyRecords = state.a1cDailyRecords,
                        manualRecords = state.manualRecords,
                        modifier = Modifier.padding(end = 52.dp),
                    )

                    WeightTrendChart(
                        state.weightRecords,
                        state.stepDailyRecords,
                        state.glucoseRecords,
                        state.a1cDailyRecords,
                    )

                    sharedText?.takeIf { it.isNotBlank() }?.let { text ->
                        DebugSection(title = "共有テキスト") {
                            sharedTextImportStatus?.let { status ->
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppMutedBlue,
                                )
                            }
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppText,
                            )
                        }
                    }
                }
            }
        }
        if (!showSettings && !showManualInput) {
            FloatingActionButton(
                onClick = { showManualInput = true },
                containerColor = AppPrimary,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(22.dp),
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MainSummaryValues(
    records: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    glucoseRecords: List<DebugGlucoseRecord>,
    a1cDailyRecords: List<DebugA1cDaily>,
    manualRecords: List<ManualHealthRecord>,
    modifier: Modifier = Modifier,
) {
    val latestRecord = records.maxByOrNull { it.measuredAt }
    val latestSteps = dailySteps.maxByOrNull { it.targetDate }
    val latestFastingGlucose = glucoseRecords.fastingGlucoseRecords().firstOrNull()
    val latestA1c = a1cDailyRecords.maxByOrNull { it.targetDate }
    val latestWaist = manualRecords.latestManualValue(ManualRecordType.Waist)
    val averageBloodPressure = manualRecords.latestDailyBloodPressureAverageText()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryValue("体重", latestRecord?.let { "${formatDecimal(it.weightKg)}kg" } ?: "-", ChartBlue)
            SummaryValue("歩数", latestSteps?.let { "${it.steps}歩" } ?: "-", ChartStepText)
            SummaryValue(
                "血糖",
                latestFastingGlucose?.let { "${formatDecimal(it.bloodGlucoseMgDl)}" } ?: "-",
                ChartGlucose,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryValue("A1c", latestA1c?.let { "${formatDecimal(it.a1cPercent)}%" } ?: "-", ChartA1c)
            SummaryValue("腹囲", latestWaist ?: "-", ChartSummary)
            SummaryValue("血圧", averageBloodPressure ?: "-", AppMutedBlue)
        }
    }
}

@Composable
private fun SummaryValue(
    label: String,
    value: String,
    color: Color,
) {
    Text(
        text = "$label $value",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        fontSize = 13.sp,
    )
}

@Composable
private fun SettingsGearIcon(
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val strokeWidth = 3.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = size.minDimension * 0.26f
        val toothStartRadius = size.minDimension * 0.36f
        val toothEndRadius = size.minDimension * 0.44f

        repeat(8) { index ->
            val angle = (PI / 4.0 * index).toFloat()
            val start = Offset(
                x = center.x + cos(angle) * toothStartRadius,
                y = center.y + sin(angle) * toothStartRadius,
            )
            val end = Offset(
                x = center.x + cos(angle) * toothEndRadius,
                y = center.y + sin(angle) * toothEndRadius,
            )
            drawLine(
                color = AppText,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        drawCircle(
            color = AppText,
            radius = ringRadius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
    }
}

@Composable
private fun LoadingScreen(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "データロード中",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onSaveExternalWorkbook: () -> Unit,
    externalSaveStatus: String?,
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
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
                shape = CircleShape,
                modifier = Modifier.defaultMinSize(minWidth = 128.dp, minHeight = 52.dp),
            ) {
                Text(if (state.isLoading) "読み込み中" else "データ更新")
            }
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
private fun DebugSection(
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

private object ColumnScopeMarker

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
private fun ManualDataScreen(
    weightRecords: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    glucoseRecords: List<DebugGlucoseRecord>,
    manualRecords: List<ManualHealthRecord>,
    invalidatedRecords: List<InvalidatedGraphRecord>,
    onSave: (ManualHealthRecordDraft) -> Unit,
    onInvalidateManual: (String) -> Unit,
    onRestoreManual: (String) -> Unit,
    onDeleteManual: (String) -> Unit,
    onInvalidate: (String, String) -> Unit,
    onRestore: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(ManualRecordType.Weight) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedTimeBand by remember { mutableStateOf("朝") }
    var showDatePicker by remember { mutableStateOf(false) }
    var primaryValue by remember { mutableStateOf("") }
    var secondaryValue by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    val labels = selectedType.inputLabels()
    val selectedRecords = remember(selectedType, weightRecords, dailySteps, glucoseRecords, manualRecords, invalidatedRecords) {
        selectedType.toGraphDataItems(weightRecords, dailySteps, glucoseRecords, manualRecords, invalidatedRecords)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "手入力",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        OutlinedButton(onClick = onBack) {
            Text("戻る")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ManualRecordType.entries.chunked(3).forEach { rowTypes ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTypes.forEach { type ->
                    if (type == selectedType) {
                        Button(
                            onClick = {
                                selectedType = type
                                selectedTimeBand = type.defaultManualTimeBand()
                                inputError = null
                                secondaryValue = ""
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
                        ) {
                            Text(type.label)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                selectedType = type
                                selectedTimeBand = type.defaultManualTimeBand()
                                inputError = null
                                secondaryValue = ""
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText),
                        ) {
                            Text(type.label)
                        }
                    }
                }
            }
        }
    }

    DebugSection(title = "${selectedType.label}の入力") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                Text("前日")
            }
            OutlinedButton(onClick = { showDatePicker = true }) {
                Text(selectedDate.toString())
            }
            OutlinedButton(onClick = { selectedDate = selectedDate.plusDays(1) }) {
                Text("翌日")
            }
        }
        val timeBandOptions = selectedType.manualInputTimeBandOptions()
        if (timeBandOptions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                timeBandOptions.forEach { timeBand ->
                    if (timeBand == selectedTimeBand) {
                        Button(
                            onClick = { selectedTimeBand = timeBand },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
                        ) {
                            Text(timeBand)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { selectedTimeBand = timeBand },
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText),
                        ) {
                            Text(timeBand)
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = primaryValue,
            onValueChange = { primaryValue = it },
            label = { Text(labels.first) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        labels.second?.let { secondaryLabel ->
            OutlinedTextField(
                value = secondaryValue,
                onValueChange = { secondaryValue = it },
                label = { Text(secondaryLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        inputError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = ChartGlucose,
            )
        }
        Button(
            onClick = {
                val measuredAt = selectedDate.atTime(selectedType.manualInputTime(selectedTimeBand))
                val valueText = selectedType.formatManualValue(primaryValue, secondaryValue)
                if (valueText == null) {
                    inputError = "値を確認してください"
                } else {
                    onSave(
                        ManualHealthRecordDraft(
                            type = selectedType,
                            measuredAt = measuredAt,
                            valueText = valueText,
                        ),
                    )
                    primaryValue = ""
                    secondaryValue = ""
                    inputError = null
                }
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
        ) {
            Text("保存")
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.toEpochMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        datePickerState.selectedDateMillis?.toLocalDateFromEpochMillis()?.let { date ->
                            selectedDate = date
                        }
                        showDatePicker = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
                ) {
                    Text("選択")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDatePicker = false }) {
                    Text("キャンセル")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    DebugSection(title = "${selectedType.label}のデータ一覧") {
        if (selectedRecords.isEmpty()) {
            Text(
                text = "データはありません",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            selectedRecords.forEach { record ->
                ManualRecordRow(
                    record = record,
                    onInvalidate = onInvalidate,
                    onRestore = onRestore,
                    onDelete = onDelete,
                    onInvalidateManual = onInvalidateManual,
                    onRestoreManual = onRestoreManual,
                    onDeleteManual = onDeleteManual,
                )
            }
        }
    }
}

@Composable
private fun ManualRecordRow(
    record: GraphDataItem,
    onInvalidate: (String, String) -> Unit,
    onRestore: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onInvalidateManual: (String) -> Unit,
    onRestoreManual: (String) -> Unit,
    onDeleteManual: (String) -> Unit,
) {
    var showInvalidateConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (record.invalidatedAt == null) AppText else ChartLabel,
            )
            record.invalidatedAt?.let {
                Text(
                    text = "無効",
                    style = MaterialTheme.typography.labelMedium,
                    color = ChartLabel,
                )
            }
        }
        if (record.invalidatedAt == null) {
            IconButton(onClick = { showInvalidateConfirm = true }) {
                TrashIcon()
            }
        } else {
            RestoreHoldButton(
                onRestoreClick = { showRestoreConfirm = true },
                onDeleteHold = { showDeleteConfirm = true },
                holdKey = record.uniqueKey,
            )
        }
    }

    if (showInvalidateConfirm) {
        AlertDialog(
            onDismissRequest = { showInvalidateConfirm = false },
            title = { Text("データを無効にしますか") },
            text = { Text(record.text) },
            confirmButton = {
                Button(
                    onClick = {
                        showInvalidateConfirm = false
                        if (record.recordType == MANUAL_RECORD_TYPE) {
                            onInvalidateManual(record.uniqueKey)
                        } else {
                            onInvalidate(record.recordType, record.uniqueKey)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ChartGlucose),
                ) {
                    Text("無効にする")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showInvalidateConfirm = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("データを復活しますか") },
            text = { Text(record.text) },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirm = false
                        if (record.recordType == MANUAL_RECORD_TYPE) {
                            onRestoreManual(record.uniqueKey)
                        } else {
                            onRestore(record.recordType, record.uniqueKey)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
                ) {
                    Text("復活する")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRestoreConfirm = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    text = "削除しますか",
                    color = DeleteOrange,
                )
            },
            text = {
                Text(
                    text = record.text,
                    color = DeleteOrange,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        if (record.recordType == MANUAL_RECORD_TYPE) {
                            onDeleteManual(record.uniqueKey)
                        } else {
                            onDelete(record.recordType, record.uniqueKey)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeleteOrange),
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun RestoreHoldButton(
    onRestoreClick: () -> Unit,
    onDeleteHold: () -> Unit,
    holdKey: String,
) {
    Surface(
        modifier = Modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 36.dp)
            .pointerInput(holdKey) {
                detectTapGestures(
                    onPress = {
                        val releasedBeforeDeleteMode = withTimeoutOrNull(DELETE_PRESS_MILLIS) {
                            tryAwaitRelease()
                        }
                        if (releasedBeforeDeleteMode == null) {
                            onDeleteHold()
                            tryAwaitRelease()
                        } else if (releasedBeforeDeleteMode) {
                            onRestoreClick()
                        }
                    },
                )
            },
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = AppText,
        border = BorderStroke(1.dp, AppText),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("復活")
        }
    }
}

@Composable
private fun TrashIcon(
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(22.dp)) {
        val strokeWidth = 2.dp.toPx()
        val left = size.width * 0.28f
        val right = size.width * 0.72f
        val top = size.height * 0.34f
        val bottom = size.height * 0.78f
        drawLine(ChartGlucose, Offset(size.width * 0.25f, top), Offset(size.width * 0.75f, top), strokeWidth, cap = StrokeCap.Round)
        drawLine(ChartGlucose, Offset(size.width * 0.42f, size.height * 0.22f), Offset(size.width * 0.58f, size.height * 0.22f), strokeWidth, cap = StrokeCap.Round)
        drawLine(ChartGlucose, Offset(left, top), Offset(left + 1.dp.toPx(), bottom), strokeWidth, cap = StrokeCap.Round)
        drawLine(ChartGlucose, Offset(right, top), Offset(right - 1.dp.toPx(), bottom), strokeWidth, cap = StrokeCap.Round)
        drawLine(ChartGlucose, Offset(left + 1.dp.toPx(), bottom), Offset(right - 1.dp.toPx(), bottom), strokeWidth, cap = StrokeCap.Round)
        drawLine(ChartGlucose, Offset(size.width * 0.43f, top + 4.dp.toPx()), Offset(size.width * 0.43f, bottom - 3.dp.toPx()), strokeWidth * 0.75f, cap = StrokeCap.Round)
        drawLine(ChartGlucose, Offset(size.width * 0.57f, top + 4.dp.toPx()), Offset(size.width * 0.57f, bottom - 3.dp.toPx()), strokeWidth * 0.75f, cap = StrokeCap.Round)
    }
}

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
private fun WeightTrendChart(
    records: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    glucoseRecords: List<DebugGlucoseRecord>,
    a1cDailyRecords: List<DebugA1cDaily>,
) {
    val sortedRecords = remember(records) { records.sortedBy { it.measuredAt } }
    var selectedRange by remember { mutableStateOf(WeightChartRange.TwoWeeks) }
    var chartEndAt by remember(sortedRecords, selectedRange) {
        mutableStateOf(sortedRecords.lastOrNull()?.measuredAt)
    }
    val chartWindow = remember(sortedRecords, selectedRange, chartEndAt) {
        selectedRange.window(sortedRecords, chartEndAt)
    }
    val chartRecords = remember(sortedRecords, chartWindow) {
        chartWindow?.let { window ->
            sortedRecords.filter { !it.measuredAt.isBefore(window.startAt) && !it.measuredAt.isAfter(window.endAt) }
        } ?: emptyList()
    }
    val chartPoints = remember(chartRecords) { chartRecords.toChartWeightPoints() }
    var selectedDate by remember(chartPoints) { mutableStateOf<LocalDate?>(null) }
    val selectedDay = remember(chartPoints, dailySteps, selectedDate) {
        selectedDate?.let { date ->
            val dayPoints = chartPoints.filter { it.targetDate == date }
            if (dayPoints.isEmpty()) {
                null
            } else {
                ChartDaySelection(
                    date = date,
                    morning = dayPoints.firstOrNull { it.timeBand == "朝" },
                    night = dayPoints.firstOrNull { it.timeBand == "夜" },
                    steps = dailySteps.firstOrNull { it.targetDate == date },
                )
            }
        }
    }
    val latestEndAt = sortedRecords.lastOrNull()?.measuredAt
    val earliestEndAt = remember(sortedRecords, selectedRange) {
        selectedRange.minimumEndAt(sortedRecords)
    }
    val currentChartEndAt by rememberUpdatedState(chartEndAt)
    val lineColor = ChartBlue
    val selectedColor = AppPrimary
    val gridColor = ChartGrid
    val trendLineColor = ChartTrend
    val stepBarColor = ChartStepBar
    val weekBoundaryColor = ChartGrid
    val axisColor = ChartLabel
    val missingPointColor = ChartMissingPoint

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(earliestEndAt, latestEndAt) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            chartEndAt = chartEndAtAfterHorizontalDrag(
                                currentEndAt = currentChartEndAt,
                                earliestEndAt = earliestEndAt,
                                latestEndAt = latestEndAt,
                                dragAmount = dragAmount,
                                width = size.width.toFloat(),
                            )
                        }
                    }
                    .pointerInput(chartPoints, chartWindow) {
                        detectTapGestures { offset ->
                            if (chartPoints.isEmpty()) return@detectTapGestures
                            val visibleWindow = chartWindow ?: return@detectTapGestures
                            selectedDate = nearestChartIndex(
                                touchX = offset.x,
                                width = size.width.toFloat(),
                                records = chartPoints,
                                rangeStartAt = visibleWindow.startAt,
                                rangeEndAt = visibleWindow.endAt,
                                leftPadding = CHART_LEFT_PADDING_DP.dp.toPx(),
                                rightPadding = CHART_RIGHT_PADDING_DP.dp.toPx(),
                            ).let { chartPoints.getOrNull(it)?.targetDate }
                        }
                    }
            ) {
                if (chartPoints.isEmpty()) return@Canvas
                val visibleWindow = chartWindow ?: return@Canvas

                val leftPadding = CHART_LEFT_PADDING_DP.dp.toPx()
                val rightPadding = CHART_RIGHT_PADDING_DP.dp.toPx()
                val topPadding = 28.dp.toPx()
                val bottomPadding = 28.dp.toPx()
                val chartLeft = leftPadding
                val chartRight = size.width - rightPadding
                val chartTop = topPadding
                val chartBottom = size.height - bottomPadding
                val chartWidth = max(1f, chartRight - chartLeft)
                val chartHeight = max(1f, chartBottom - chartTop)
                val minWeight = floor(chartPoints.minOf { it.weightKg } - CHART_WEIGHT_LOWER_PADDING_KG)
                val maxWeight = ceil(chartPoints.maxOf { it.weightKg } + CHART_WEIGHT_UPPER_PADDING_KG)
                val weightRange = max(1.0, maxWeight - minWeight)
                val trendLine = calculateTrendLine(chartPoints)
                val averageSteps = calculateAverageSteps(dailySteps, visibleWindow)
                val glucoseChart = calculateFastingGlucoseChart(glucoseRecords, visibleWindow)
                val a1cChart = calculateA1cChart(a1cDailyRecords, visibleWindow)
                val solidWeightLines = generateSequence(maxWeight) { it - 1.0 }
                    .takeWhile { it >= minWeight }
                    .toList()
                val halfWeightLines = generateSequence(maxWeight - 0.5) { it - 1.0 }
                    .takeWhile { it > minWeight }
                    .toList()
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = axisColor.toArgb()
                    textSize = 12.sp.toPx()
                }
                val monthLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = axisColor.toArgb()
                    textSize = 9.sp.toPx()
                }
                val trendSummaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ChartSummary.toArgb()
                    textSize = 13.sp.toPx()
                }
                val glucosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ChartGlucose.toArgb()
                    textSize = 12.sp.toPx()
                }
                val a1cPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ChartA1c.toArgb()
                    textSize = 12.sp.toPx()
                }
                val dashedGrid = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))

            fun xAtTime(measuredAt: LocalDateTime): Float {
                val totalMillis = max(1L, Duration.between(visibleWindow.startAt, visibleWindow.endAt).toMillis())
                val elapsedMillis = Duration.between(
                    visibleWindow.startAt,
                    measuredAt.coerceIn(visibleWindow.startAt, visibleWindow.endAt),
                ).toMillis()
                return chartLeft + chartWidth * elapsedMillis.toFloat() / totalMillis
            }

            fun xAt(index: Int): Float {
                return xAtTime(chartPoints[index].measuredAt)
            }

            fun yAt(weightKg: Double): Float {
                val ratio = ((weightKg - minWeight) / weightRange).toFloat()
                return chartBottom - chartHeight * ratio
            }

            fun stepYAt(steps: Float): Float {
                val ratio = (steps / STEP_CHART_MAX_STEPS).coerceIn(0f, 1f)
                return chartBottom - chartHeight * ratio
            }

            fun a1cYAt(value: Double): Float {
                val ratio = ((value - A1C_CHART_MIN) / (A1C_CHART_MAX - A1C_CHART_MIN)).toFloat()
                    .coerceIn(0f, 1f)
                return chartBottom - chartHeight * ratio
            }

            halfWeightLines.forEach { weightKg ->
                val y = yAt(weightKg)
                drawLine(
                    color = gridColor,
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashedGrid,
                )
            }
            solidWeightLines.forEach { weightKg ->
                val y = yAt(weightKg)
                drawLine(
                    color = gridColor,
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            chartPoints.zipWithNext().forEachIndexed { index, pair ->
                val (previous, current) = pair
                if (previous.targetDate.dayOfWeek == DayOfWeek.SUNDAY &&
                    current.targetDate.dayOfWeek == DayOfWeek.MONDAY
                ) {
                    val x = (xAt(index) + xAt(index + 1)) / 2f
                    drawLine(
                        color = weekBoundaryColor,
                        start = Offset(x, chartTop),
                        end = Offset(x, chartBottom),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }

            drawContext.canvas.nativeCanvas.apply {
                labelPaint.textAlign = Paint.Align.RIGHT
                solidWeightLines.forEach { weightKg ->
                    val y = yAt(weightKg)
                    drawText("${formatDecimal(weightKg)}kg", chartLeft - 8.dp.toPx(), y + 4.dp.toPx(), labelPaint)
                }
                labelPaint.textAlign = Paint.Align.LEFT
                drawText("1", chartRight + 8.dp.toPx(), stepYAt(STEP_REFERENCE_STEPS), labelPaint)
                labelPaint.textAlign = Paint.Align.CENTER
                monthLabelPaint.textAlign = Paint.Align.LEFT
                val visibleStartDate = visibleWindow.startAt.toLocalDate()
                val visibleStartMonthText = "${visibleStartDate.monthValue}月"
                drawText(
                    visibleStartMonthText,
                    chartLeft,
                    chartBottom + 11.dp.toPx(),
                    monthLabelPaint,
                )
                val monthLabelMinX = chartLeft + monthLabelPaint.measureText(visibleStartMonthText) + 8.dp.toPx()
                val visibleDates = generateSequence(visibleWindow.startAt.toLocalDate()) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(visibleWindow.endAt.toLocalDate()) }
                    .toList()
                val dateLabelPositions = visibleDates
                    .filter { it.shouldShowChartDateNumber() }
                    .map { date ->
                        val x = xAtTime(date.atStartOfDay().plusHours(12))
                            .coerceIn(chartLeft + 6.dp.toPx(), chartRight - 6.dp.toPx())
                        val text = date.dayOfMonth.toString()
                        ChartDateLabel(date, x, labelPaint.measureText(text))
                    }
                val hiddenDateLabels = dateLabelPositions
                    .zipWithNext()
                    .mapNotNull { (previous, current) ->
                        if (previous.right + 4.dp.toPx() > current.left) previous.date else null
                    }
                    .toSet()
                visibleDates.forEach { date ->
                        val x = xAtTime(date.atStartOfDay().plusHours(12))
                            .coerceIn(chartLeft + 6.dp.toPx(), chartRight - 6.dp.toPx())
                        if (date.dayOfMonth == 1 && date != visibleStartDate && x >= monthLabelMinX) {
                            drawText(
                                "${date.monthValue}月",
                                x,
                                chartBottom + 11.dp.toPx(),
                                monthLabelPaint,
                            )
                        }
                        if (date.shouldShowChartDateNumber() && date !in hiddenDateLabels) {
                            drawText(
                                date.dayOfMonth.toString(),
                                x,
                                chartBottom + 22.dp.toPx(),
                                labelPaint,
                            )
                        } else {
                            drawCircle(
                                x,
                                chartBottom + 17.dp.toPx(),
                                1.5.dp.toPx(),
                                labelPaint,
                            )
                        }
                }
                trendLine?.let {
                    trendSummaryPaint.textAlign = Paint.Align.LEFT
                    drawText(
                        formatTrendChange(it, selectedRange),
                        chartLeft + 8.dp.toPx(),
                        chartTop + 16.dp.toPx(),
                        trendSummaryPaint,
                    )
                }
                drawText(
                    "平均歩数 ${averageSteps?.let { "%,d歩".format(it) } ?: "-"}",
                    chartLeft + 8.dp.toPx(),
                    chartTop + 34.dp.toPx(),
                    trendSummaryPaint,
                )
                drawText(
                    "平均血糖 ${glucoseChart?.let { "${formatDecimal(it.weightedAverageMgDl)} mg/dL" } ?: "-"}",
                    chartLeft + 8.dp.toPx(),
                    chartTop + 52.dp.toPx(),
                    trendSummaryPaint,
                )
            }

            calculateStepBars(dailySteps, visibleWindow).forEach { stepBar ->
                val stepRatio = (stepBar.steps.toFloat() / STEP_CHART_MAX_STEPS).coerceIn(0f, 1f)
                val barHeight = chartHeight * stepRatio
                val barWidth = 12.dp.toPx()
                drawRect(
                    color = stepBarColor,
                    topLeft = Offset(xAtTime(stepBar.centerAt) - barWidth / 2f, chartBottom - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                )
            }

            glucoseChart?.let { chart ->
                val glucoseY = chartBottom - 54.dp.toPx()
                drawLine(
                    color = ChartGlucose,
                    start = Offset(chartLeft, glucoseY),
                    end = Offset(chartRight, glucoseY),
                    strokeWidth = 1.5.dp.toPx(),
                )
                chart.visibleRecords.forEach { record ->
                    drawCircle(
                        color = ChartGlucose,
                        radius = 4.dp.toPx(),
                        center = Offset(xAtTime(record.targetDate.atStartOfDay().plusHours(12)), glucoseY),
                    )
                }
                drawContext.canvas.nativeCanvas.apply {
                    glucosePaint.textAlign = Paint.Align.RIGHT
                    drawText(
                        "${formatDecimal(chart.weightedAverageMgDl)}",
                        chartRight - 4.dp.toPx(),
                        glucoseY - 4.dp.toPx(),
                        glucosePaint,
                    )
                }
            }

            a1cChart?.let { chart ->
                val linePoints = chart.lineRecords
                if (linePoints.isNotEmpty()) {
                    val a1cPath = Path()
                    linePoints.forEachIndexed { index, record ->
                        val point = Offset(xAtTime(record.measuredAt), a1cYAt(record.value))
                        if (index == 0) {
                            a1cPath.moveTo(point.x, point.y)
                        } else {
                            a1cPath.lineTo(point.x, point.y)
                        }
                    }
                    drawPath(
                        path = a1cPath,
                        color = ChartA1c,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                if (chart.visibleRecords.isNotEmpty()) {
                    chart.visibleRecords.forEach { record ->
                        drawCircle(
                            color = ChartA1c,
                            radius = 4.dp.toPx(),
                            center = Offset(xAtTime(record.measuredAt), a1cYAt(record.value)),
                        )
                    }
                }
                chart.latestRecord?.let { latest ->
                    val latestX = xAtTime(latest.measuredAt)
                    val latestY = a1cYAt(latest.value)
                    drawLine(
                        color = ChartA1c,
                        start = Offset(latestX, latestY),
                        end = Offset(chartRight, latestY),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawContext.canvas.nativeCanvas.apply {
                        a1cPaint.textAlign = Paint.Align.RIGHT
                        drawText(
                            formatDecimal(latest.value),
                            chartRight - 4.dp.toPx(),
                            latestY - 4.dp.toPx(),
                            a1cPaint,
                        )
                    }
                }
            }

            val path = Path()
            chartPoints.forEachIndexed { index, record ->
                val point = Offset(xAt(index), yAt(record.weightKg))
                if (index == 0) {
                    path.moveTo(point.x, point.y)
                } else {
                    path.lineTo(point.x, point.y)
                }
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx()),
            )

            val missingPointStroke = Stroke(width = 2.dp.toPx())
            calculateMissingWeightPoints(chartPoints).forEach { missingPoint ->
                drawCircle(
                    color = missingPointColor,
                    radius = 4.dp.toPx(),
                    center = Offset(xAtTime(missingPoint.measuredAt), yAt(missingPoint.weightKg)),
                    style = missingPointStroke,
                )
            }

            trendLine?.let {
                drawLine(
                    color = trendLineColor,
                    start = Offset(xAt(0), yAt(it.startWeightKg)),
                    end = Offset(xAt(chartPoints.lastIndex), yAt(it.endWeightKg)),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            chartPoints.forEachIndexed { index, record ->
                val center = Offset(xAt(index), yAt(record.weightKg))
                if (record.isMorning()) {
                    drawCircle(
                        color = lineColor,
                        radius = 4.dp.toPx(),
                        center = center,
                    )
                } else {
                    drawCircle(
                        color = AppBackground,
                        radius = 4.dp.toPx(),
                        center = center,
                    )
                    drawCircle(
                        color = lineColor,
                        radius = 4.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

                selectedDay?.points?.forEach { record ->
                    chartPoints.indexOf(record).takeIf { it >= 0 }?.let { index ->
                        val selectedPoint = Offset(xAt(index), yAt(record.weightKg))
                        drawLine(
                            color = selectedColor,
                            start = Offset(selectedPoint.x, chartTop),
                            end = Offset(selectedPoint.x, chartBottom),
                            strokeWidth = 1.dp.toPx(),
                        )
                        drawCircle(
                            color = selectedColor,
                            radius = 6.dp.toPx(),
                            center = selectedPoint,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            selectedDay?.let { day ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 42.dp, end = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = PopupBackground,
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "${day.date.monthValue}月${day.date.dayOfMonth}日",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppText,
                        )
                        Text(
                            text = "朝 ${day.morning.weightText()}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ChartBlue,
                        )
                        Text(
                            text = "夜 ${day.night.weightText()}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ChartBlue,
                        )
                        Text(
                            text = "差 ${day.weightDifferenceText()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppText,
                        )
                        Text(
                            text = "歩数 ${day.steps?.steps?.let { "${it}歩" } ?: "-"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppText,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WeightChartRange.entries.forEach { range ->
                WeightChartRangeButton(
                    range = range,
                    selected = range == selectedRange,
                    onClick = { selectedRange = range },
                )
            }
        }
    }
}

@Composable
private fun WeightChartRangeButton(
    range: WeightChartRange,
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
            modifier = Modifier.defaultMinSize(minWidth = 72.dp, minHeight = 40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(range.label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppText),
            modifier = Modifier.defaultMinSize(minWidth = 72.dp, minHeight = 40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(range.label)
        }
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

private fun HealthConnectAvailability.displayText(): String {
    return when (this) {
        HealthConnectAvailability.Checking -> "確認中"
        HealthConnectAvailability.Available -> "利用可能"
        is HealthConnectAvailability.Unavailable -> reason
    }
}

private fun PermissionState.displayText(): String {
    return when (this) {
        PermissionState.Unknown -> "不明"
        is PermissionState.Granted -> "許可済み ($grantedCount/$requiredCount)"
        is PermissionState.Missing ->
            "不足 ($grantedCount/$requiredCount): ${missingPermissions.joinToString { it.toPermissionLabel() }}"
    }
}

private fun String.toPermissionLabel(): String {
    return when {
        contains("READ_WEIGHT") -> "体重の読み取り"
        contains("READ_BLOOD_GLUCOSE") -> "血糖値の読み取り"
        contains("READ_STEPS") -> "歩数の読み取り"
        else -> this
    }
}

private fun LocalDateTime.formatDateTime(): String =
    format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

private fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(DATE_PICKER_ZONE).toInstant().toEpochMilli()

private fun Long.toLocalDateFromEpochMillis(): LocalDate =
    Instant.ofEpochMilli(this).atZone(DATE_PICKER_ZONE).toLocalDate()

private fun ManualRecordType.inputLabels(): Pair<String, String?> {
    return when (this) {
        ManualRecordType.Weight -> "体重 kg" to null
        ManualRecordType.Steps -> "歩数" to null
        ManualRecordType.BloodGlucose -> "血糖値 mg/dL" to null
        ManualRecordType.BloodPressure -> "収縮期 mmHg" to "拡張期 mmHg"
        ManualRecordType.Waist -> "腹囲 cm" to null
        ManualRecordType.A1c -> "A1c %" to null
    }
}

private fun ManualRecordType.formatManualValue(primaryValue: String, secondaryValue: String): String? {
    val primary = primaryValue.trim()
    val secondary = secondaryValue.trim()
    return when (this) {
        ManualRecordType.Weight ->
            primary.toDoubleOrNull()?.let { "${formatDecimal(it)} kg" }
        ManualRecordType.Steps ->
            primary.toLongOrNull()?.let { "${it}歩" }
        ManualRecordType.BloodGlucose ->
            primary.toDoubleOrNull()?.let { "${formatDecimal(it)} mg/dL" }
        ManualRecordType.BloodPressure -> {
            val systolic = primary.toLongOrNull()
            val diastolic = secondary.toLongOrNull()
            if (systolic == null || diastolic == null) null else "$systolic/$diastolic mmHg"
        }
        ManualRecordType.Waist ->
            primary.toDoubleOrNull()?.let { "${formatDecimal(it)} cm" }
        ManualRecordType.A1c ->
            primary.toDoubleOrNull()?.let { "${formatDecimal(it)} %" }
    }
}

private fun ManualRecordType.manualInputTimeBandOptions(): List<String> {
    return when (this) {
        ManualRecordType.Steps,
        ManualRecordType.Waist,
        ManualRecordType.A1c -> emptyList()
        ManualRecordType.BloodPressure -> listOf("朝", "夜")
        else -> listOf("朝", "昼", "夜")
    }
}

private fun ManualRecordType.defaultManualTimeBand(): String {
    return when (this) {
        ManualRecordType.BloodPressure -> "朝"
        else -> "朝"
    }
}

private fun ManualRecordType.manualInputTime(timeBand: String): LocalTime {
    return when (this) {
        ManualRecordType.Steps,
        ManualRecordType.Waist,
        ManualRecordType.A1c -> LocalTime.NOON
        else -> when (timeBand) {
            "朝" -> LocalTime.of(7, 0)
            "昼" -> LocalTime.of(12, 0)
            else -> LocalTime.of(20, 0)
        }
    }
}

private fun ManualRecordType.toGraphDataItems(
    weightRecords: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    glucoseRecords: List<DebugGlucoseRecord>,
    manualRecords: List<ManualHealthRecord>,
    invalidatedRecords: List<InvalidatedGraphRecord>,
): List<GraphDataItem> {
    val activeItems = when (this) {
        ManualRecordType.Weight -> weightRecords
            .sortedByDescending { it.measuredAt }
            .map { record ->
                GraphDataItem(
                    recordType = "weight",
                    uniqueKey = record.weightUniqueKey(),
                    text = "${record.measuredAt.formatDateTime()}  ${formatDecimal(record.weightKg)} kg / ${record.timeBand}",
                    measuredAt = record.measuredAt,
                    invalidatedAt = null,
                )
            }
        ManualRecordType.Steps -> dailySteps
            .sortedByDescending { it.targetDate }
            .map { steps ->
                GraphDataItem(
                    recordType = "steps",
                    uniqueKey = steps.targetDate.toString(),
                    text = "${steps.targetDate}  ${steps.steps}歩",
                    measuredAt = steps.targetDate.atStartOfDay(),
                    invalidatedAt = null,
                )
            }
        ManualRecordType.BloodGlucose -> glucoseRecords
            .sortedByDescending { it.measuredAt }
            .map { record ->
                GraphDataItem(
                    recordType = "glucose",
                    uniqueKey = record.glucoseUniqueKey(),
                    text = "${record.measuredAt.formatDateTime()}  ${formatDecimal(record.bloodGlucoseMgDl)} mg/dL / ${record.mealRelation}",
                    measuredAt = record.measuredAt,
                    invalidatedAt = null,
                )
            }
        ManualRecordType.BloodPressure,
        ManualRecordType.Waist,
        ManualRecordType.A1c -> manualRecords
            .filter { it.type == this }
            .sortedByDescending { it.measuredAt }
            .map { record ->
                GraphDataItem(
                    recordType = MANUAL_RECORD_TYPE,
                    uniqueKey = record.id,
                    text = "${record.measuredAt.formatDateTime()}  ${record.valueText}",
                    measuredAt = record.measuredAt,
                    invalidatedAt = record.invalidatedAt,
                )
            }
    }
    val invalidatedItems = invalidatedRecords
        .filter { it.manualType == this }
        .map { record ->
            GraphDataItem(
                recordType = record.recordType,
                uniqueKey = record.uniqueKey,
                text = record.text,
                measuredAt = record.measuredAt,
                invalidatedAt = record.invalidatedAt,
            )
        }
    return (activeItems + invalidatedItems).sortedByDescending { it.measuredAt }
}

private fun DebugWeightRecord.weightUniqueKey(): String {
    return stableHealthRecordKey("weight", healthConnectId)
        ?: "weight|$measuredAt|$sourcePackageName|$weightKg"
}

private fun DebugGlucoseRecord.glucoseUniqueKey(): String {
    return stableHealthRecordKey("glucose", healthConnectId)
        ?: "glucose|$measuredAt|$sourcePackageName|$bloodGlucoseMgDl|$mealRelation"
}

private fun stableHealthRecordKey(recordType: String, healthConnectId: String): String? {
    if (healthConnectId.isBlank() || healthConnectId == UNKNOWN_HEALTH_VALUE) return null
    return "$recordType|$healthConnectId"
}

private fun formatDecimal(value: Double): String {
    val roundedOneDecimal = (value * 10.0).roundToInt() / 10.0
    return if (roundedOneDecimal % 1.0 == 0.0) {
        roundedOneDecimal.toInt().toString()
    } else {
        roundedOneDecimal.toString()
    }
}

private fun List<ManualHealthRecord>.latestManualValue(type: ManualRecordType): String? {
    return filter { it.type == type && it.invalidatedAt == null }
        .maxByOrNull { it.measuredAt }
        ?.valueText
}

private fun List<ManualHealthRecord>.latestDailyBloodPressureAverageText(): String? {
    return filter { it.type == ManualRecordType.BloodPressure && it.invalidatedAt == null }
        .groupBy { it.measuredAt.toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .values
        .firstNotNullOfOrNull { records ->
            val morning = records.latestBloodPressureInTimeBand("朝")
            val night = records.latestBloodPressureInTimeBand("夜")
            if (morning == null || night == null) {
                null
            } else {
                val systolic = ((morning.systolic + night.systolic) / 2.0).roundToInt()
                val diastolic = ((morning.diastolic + night.diastolic) / 2.0).roundToInt()
                "$systolic/$diastolic"
            }
        }
}

private fun List<ManualHealthRecord>.latestBloodPressureInTimeBand(timeBand: String): BloodPressureValue? {
    return filter { it.measuredAt.toTimeBand() == timeBand }
        .maxByOrNull { it.measuredAt }
        ?.valueText
        ?.toBloodPressureValue()
}

private fun String.toBloodPressureValue(): BloodPressureValue? {
    val values = removeSuffix(" mmHg").split("/")
    if (values.size != 2) return null
    return BloodPressureValue(
        systolic = values[0].trim().toIntOrNull() ?: return null,
        diastolic = values[1].trim().toIntOrNull() ?: return null,
    )
}

private fun LocalDateTime.toTimeBand(): String {
    return when (hour) {
        in 4..11 -> "朝"
        in 12..17 -> "昼"
        else -> "夜"
    }
}

private data class BloodPressureValue(
    val systolic: Int,
    val diastolic: Int,
)

private enum class WeightChartRange(
    val label: String,
    private val startAt: (LocalDateTime) -> LocalDateTime,
    private val endAt: (LocalDateTime) -> LocalDateTime,
) {
    SixMonths("半年", { latestAt -> latestAt.minusMonths(6) }, { startAt -> startAt.plusMonths(6) }),
    OneMonth("1か月", { latestAt -> latestAt.minusMonths(1) }, { startAt -> startAt.plusMonths(1) }),
    TwoWeeks("2週間", { latestAt -> latestAt.minusWeeks(2) }, { startAt -> startAt.plusWeeks(2) }),
    OneWeek("1週間", { latestAt -> latestAt.minusWeeks(1) }, { startAt -> startAt.plusWeeks(1) });

    fun window(records: List<DebugWeightRecord>, visibleEndAt: LocalDateTime?): ChartTimeWindow? {
        val rangeEndAt = visibleEndAt ?: records.lastOrNull()?.measuredAt ?: return null
        val rangeStartAt = startAt(rangeEndAt)
        return ChartTimeWindow(
            startAt = rangeStartAt.minusDays(CHART_EMPTY_EDGE_PADDING_DAYS),
            endAt = rangeEndAt.plusDays(CHART_EMPTY_EDGE_PADDING_DAYS),
        )
    }

    fun durationAt(endAt: LocalDateTime): Duration {
        return Duration.between(startAt(endAt), endAt)
    }

    fun minimumEndAt(records: List<DebugWeightRecord>): LocalDateTime? {
        val firstAt = records.firstOrNull()?.measuredAt ?: return null
        val latestAt = records.lastOrNull()?.measuredAt ?: return null
        val endForFirstRecord = endAt(firstAt)
        return if (endForFirstRecord.isAfter(latestAt)) latestAt else endForFirstRecord
    }
}

private data class WeightTrendLine(
    val startWeightKg: Double,
    val endWeightKg: Double,
)

private data class ChartWeightPoint(
    val measuredAt: LocalDateTime,
    val targetDate: LocalDate,
    val timeBand: String,
    val weightKg: Double,
    val sourceCount: Int,
) {
    val isAverage: Boolean
        get() = sourceCount >= 2
}

private data class ChartDaySelection(
    val date: LocalDate,
    val morning: ChartWeightPoint?,
    val night: ChartWeightPoint?,
    val steps: DebugStepDaily?,
) {
    val points: List<ChartWeightPoint>
        get() = listOfNotNull(morning, night)
}

private data class ChartTimeWindow(
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
)

private data class StepBar(
    val centerAt: LocalDateTime,
    val steps: Long,
)

private data class FastingGlucoseChart(
    val weightedAverageMgDl: Double,
    val visibleRecords: List<DebugGlucoseRecord>,
)

private data class A1cChart(
    val visibleRecords: List<A1cChartRecord>,
    val lineRecords: List<A1cChartRecord>,
    val latestRecord: A1cChartRecord?,
)

private data class A1cChartRecord(
    val measuredAt: LocalDateTime,
    val value: Double,
)

private data class ChartDateLabel(
    val date: LocalDate,
    val x: Float,
    val width: Float,
) {
    val left: Float
        get() = x - width / 2f
    val right: Float
        get() = x + width / 2f
}

private data class GraphDataItem(
    val recordType: String,
    val uniqueKey: String,
    val text: String,
    val measuredAt: LocalDateTime,
    val invalidatedAt: LocalDateTime?,
)

private fun ChartWeightPoint.isMorning(): Boolean =
    timeBand == "朝"

private fun ChartWeightPoint?.weightText(): String =
    this?.let { "${formatDecimal(it.weightKg)} kg" } ?: "-"

private fun ChartDaySelection.weightDifferenceText(): String {
    val morningWeight = morning?.weightKg ?: return "-"
    val nightWeight = night?.weightKg ?: return "-"
    return "${formatDecimal(abs(nightWeight - morningWeight))} kg"
}

private fun List<DebugWeightRecord>.weightTextFor(timeBand: String): String {
    return filter { it.timeBand == timeBand }
        .maxByOrNull { it.measuredAt }
        ?.let { "${formatDecimal(it.weightKg)}kg" }
        ?: "-"
}

private fun List<DebugWeightRecord>.toChartWeightPoints(): List<ChartWeightPoint> {
    return groupBy { it.targetDate to it.timeBand }
        .entries
        .map { (_, records) ->
            val latestRecord = records.maxBy { it.measuredAt }
            ChartWeightPoint(
                measuredAt = latestRecord.measuredAt,
                targetDate = latestRecord.targetDate,
                timeBand = latestRecord.timeBand,
                weightKg = records.sumOf { it.weightKg } / records.size,
                sourceCount = records.size,
            )
        }
        .sortedWith(compareBy<ChartWeightPoint> { it.targetDate }.thenBy { it.timeBand.chartTimeBandOrder() })
}

private fun calculateMissingWeightPoints(records: List<ChartWeightPoint>): List<ChartWeightPoint> {
    return records.zipWithNext().flatMap { (previous, current) ->
        val previousIndex = previous.chartGroupIndex()
        val currentIndex = current.chartGroupIndex()
        val missingGroupCount = currentIndex - previousIndex - 1
        if (missingGroupCount <= 0) return@flatMap emptyList()

        val weightStep = (current.weightKg - previous.weightKg) / (missingGroupCount + 1)
        (1..missingGroupCount.toInt()).map { groupOffset ->
            val group = chartGroupAt(previousIndex + groupOffset)
            ChartWeightPoint(
                measuredAt = group.targetDate.atTime(group.timeBand.chartRepresentativeTime()),
                targetDate = group.targetDate,
                timeBand = group.timeBand,
                weightKg = previous.weightKg + weightStep * groupOffset,
                sourceCount = 0,
            )
        }
    }
}

private data class ChartGroup(
    val targetDate: LocalDate,
    val timeBand: String,
)

private fun ChartWeightPoint.chartGroupIndex(): Long =
    targetDate.toEpochDay() * CHART_TIME_BAND_COUNT + timeBand.chartTimeBandOrder()

private fun chartGroupAt(index: Long): ChartGroup {
    val targetDate = LocalDate.ofEpochDay(Math.floorDiv(index, CHART_TIME_BAND_COUNT.toLong()))
    val timeBand = when (Math.floorMod(index, CHART_TIME_BAND_COUNT.toLong()).toInt()) {
        CHART_TIME_BAND_MORNING -> "朝"
        else -> "夜"
    }
    return ChartGroup(targetDate, timeBand)
}

private fun String.chartTimeBandOrder(): Int =
    if (this == "朝") CHART_TIME_BAND_MORNING else CHART_TIME_BAND_NIGHT

private fun String.chartRepresentativeTime(): LocalTime =
    if (this == "朝") LocalTime.of(8, 0) else LocalTime.of(20, 0)

private fun LocalDate.shouldShowChartDateNumber(): Boolean {
    if (isMonthEnd()) return true
    val isAroundMonthEnd = minusDays(1).isMonthEnd() || plusDays(1).isMonthEnd()
    return dayOfWeek == DayOfWeek.MONDAY && !isAroundMonthEnd
}

private fun LocalDate.isMonthEnd(): Boolean =
    plusDays(1).dayOfMonth == 1

private fun calculateTrendLine(records: List<ChartWeightPoint>): WeightTrendLine? {
    if (records.size <= 1) return null

    val count = records.size.toDouble()
    val sumX = records.indices.sumOf { it.toDouble() }
    val sumY = records.sumOf { it.weightKg }
    val sumXY = records.foldIndexed(0.0) { index, total, record ->
        total + index * record.weightKg
    }
    val sumXX = records.indices.sumOf { index ->
        val x = index.toDouble()
        x * x
    }
    val denominator = count * sumXX - sumX * sumX
    if (denominator == 0.0) return null

    val slope = (count * sumXY - sumX * sumY) / denominator
    val intercept = (sumY - slope * sumX) / count
    val lastX = records.lastIndex.toDouble()
    return WeightTrendLine(
        startWeightKg = intercept,
        endWeightKg = slope * lastX + intercept,
    )
}

private fun formatTrendChange(
    trendLine: WeightTrendLine,
    range: WeightChartRange,
): String {
    val changeKg = trendLine.endWeightKg - trendLine.startWeightKg
    val sign = if (changeKg > 0.0) "+" else ""
    return "$sign${formatDecimal(changeKg)}kg/${range.label}"
}

private fun calculateAverageSteps(
    dailySteps: List<DebugStepDaily>,
    window: ChartTimeWindow,
): Long? {
    val stepsInWindow = dailySteps
        .filter { dailyStep ->
            val targetAt = dailyStep.targetDate.atStartOfDay()
            !targetAt.isBefore(window.startAt) && !targetAt.isAfter(window.endAt)
        }
        .map { it.steps }
    if (stepsInWindow.isEmpty()) return null
    return stepsInWindow.average().roundToLong()
}

private fun calculateFastingGlucoseChart(
    glucoseRecords: List<DebugGlucoseRecord>,
    window: ChartTimeWindow,
): FastingGlucoseChart? {
    val fastingRecords = glucoseRecords.fastingGlucoseRecords()
    if (fastingRecords.isEmpty()) return null

    val weightedAverage = calculateWeightedAverageFastingGlucose(fastingRecords) ?: return null
    val visibleRecords = fastingRecords
        .filter { record ->
            val pointAt = record.targetDate.atStartOfDay().plusHours(12)
            !pointAt.isBefore(window.startAt) && !pointAt.isAfter(window.endAt)
        }
        .sortedBy { it.measuredAt }

    return FastingGlucoseChart(
        weightedAverageMgDl = weightedAverage,
        visibleRecords = visibleRecords,
    )
}

private fun calculateWeightedAverageFastingGlucose(
    glucoseRecords: List<DebugGlucoseRecord>,
): Double? {
    val fastingRecords = glucoseRecords.fastingGlucoseRecords()
    if (fastingRecords.isEmpty()) return null

    val weightedRecords = List(GLUCOSE_WEIGHT_COUNT) { index ->
        fastingRecords.getOrElse(index) { fastingRecords.last() }
    }
    return weightedRecords.zip(GLUCOSE_RECENT_WEIGHTS)
        .sumOf { (record, weight) -> record.bloodGlucoseMgDl * weight }
}

private fun List<DebugGlucoseRecord>.fastingGlucoseRecords(): List<DebugGlucoseRecord> {
    return filter { it.mealRelation.isFastingGlucoseRelation() }
        .sortedByDescending { it.measuredAt }
}

private fun String.isFastingGlucoseRelation(): Boolean =
    this == "空腹時" || this == "食前"

private fun calculateA1cChart(
    a1cDailyRecords: List<DebugA1cDaily>,
    window: ChartTimeWindow,
): A1cChart? {
    val records = a1cDailyRecords
        .map { record ->
            A1cChartRecord(record.measuredAt, record.a1cPercent)
        }
        .sortedBy { it.measuredAt }
    if (records.isEmpty()) return null
    val visibleRecords = records.filter { record ->
        !record.measuredAt.isBefore(window.startAt) && !record.measuredAt.isAfter(window.endAt)
    }
    val previousRecord = records.lastOrNull { it.measuredAt.isBefore(window.startAt) }
    val lineRecords = (listOfNotNull(previousRecord) + visibleRecords)
        .distinctBy { it.measuredAt to it.value }
    val latestRecord = records.lastOrNull { !it.measuredAt.isAfter(window.endAt) }
    return A1cChart(
        visibleRecords = visibleRecords,
        lineRecords = lineRecords,
        latestRecord = latestRecord,
    )
}

private fun calculateStepBars(
    dailySteps: List<DebugStepDaily>,
    window: ChartTimeWindow,
): List<StepBar> {
    if (dailySteps.isEmpty()) return emptyList()
    return dailySteps.mapNotNull { dailyStep ->
        if (dailyStep.steps <= 0) return@mapNotNull null
        val centerAt = dailyStep.targetDate.atStartOfDay().plusHours(12)
        if (centerAt.isBefore(window.startAt) || centerAt.isAfter(window.endAt)) return@mapNotNull null
        StepBar(
            centerAt = centerAt,
            steps = dailyStep.steps,
        )
    }
}

private fun chartEndAtAfterHorizontalDrag(
    currentEndAt: LocalDateTime?,
    earliestEndAt: LocalDateTime?,
    latestEndAt: LocalDateTime?,
    dragAmount: Float,
    width: Float,
): LocalDateTime? {
    if (earliestEndAt == null || latestEndAt == null || width <= 0f) return currentEndAt
    val baseEndAt = currentEndAt ?: latestEndAt
    val totalMillis = Duration.between(earliestEndAt, latestEndAt).toMillis()
    if (totalMillis <= 0L) return baseEndAt.coerceIn(earliestEndAt, latestEndAt)

    val dragMillis = (totalMillis * dragAmount / width).toLong()
    return baseEndAt.minus(Duration.ofMillis(dragMillis)).coerceIn(earliestEndAt, latestEndAt)
}

private fun nearestChartIndex(
    touchX: Float,
    width: Float,
    records: List<ChartWeightPoint>,
    rangeStartAt: LocalDateTime,
    rangeEndAt: LocalDateTime,
    leftPadding: Float,
    rightPadding: Float,
): Int {
    if (records.size <= 1) return 0
    val chartLeft = leftPadding
    val chartRight = width - rightPadding
    val chartWidth = max(1f, chartRight - chartLeft)
    val clampedX = touchX.coerceIn(chartLeft, chartRight)
    val totalMillis = max(1L, Duration.between(rangeStartAt, rangeEndAt).toMillis())
    var nearestIndex = 0
    var nearestDistance = Float.MAX_VALUE
    records.forEachIndexed { index, record ->
        val elapsedMillis = Duration.between(
            rangeStartAt,
            record.measuredAt.coerceIn(rangeStartAt, rangeEndAt),
        ).toMillis()
        val x = chartLeft + chartWidth * elapsedMillis.toFloat() / totalMillis
        val distance = abs(clampedX - x)
        if (distance < nearestDistance) {
            nearestDistance = distance
            nearestIndex = index
        }
    }
    return nearestIndex
}

private const val TAG = "HealthSheetSync"
private const val STEP_CHART_MAX_STEPS = 30_000f
private const val STEP_REFERENCE_STEPS = 10_000f
private const val CHART_LEFT_PADDING_DP = 44
private const val CHART_RIGHT_PADDING_DP = 8
private const val CHART_TIME_BAND_MORNING = 0
private const val CHART_TIME_BAND_NIGHT = 1
private const val CHART_TIME_BAND_COUNT = 2
private const val CHART_WEIGHT_LOWER_PADDING_KG = 1.0
private const val CHART_WEIGHT_UPPER_PADDING_KG = 1.5
private const val CHART_EMPTY_EDGE_PADDING_DAYS = 2L
private const val GLUCOSE_WEIGHT_COUNT = 3
private const val UNKNOWN_HEALTH_VALUE = "不明"
private const val MANUAL_RECORD_TYPE = "manual"
private const val DELETE_PRESS_MILLIS = 5_000L
private const val A1C_CHART_MIN = 4.0
private const val A1C_CHART_MAX = 14.0
private val DATE_PICKER_ZONE: ZoneId = ZoneId.of("UTC")
private val GLUCOSE_RECENT_WEIGHTS = listOf(0.5, 0.3, 0.2)
private val AppBackground = Color(0xFFFAFAFC)
private val HeaderBackground = Color(0xFFF2F2F3)
private val AppText = Color(0xFF202128)
private val AppMutedBlue = Color(0xFF4C6399)
private val AppPrimary = Color(0xFF4B629B)
private val DividerColor = Color(0xFFE5E5EA)
private val ChartBlue = Color(0xFF07577D)
private val ChartGrid = Color(0xFFE4E4E4)
private val ChartTrend = Color(0xFF8F8F8F)
private val ChartSummary = Color(0xFF043C5A)
private val ChartStepBar = Color(0x337E57B2)
private val ChartStepText = Color(0xFF5E3F91)
private val ChartGlucose = Color(0xFFC33A2B)
private val ChartA1c = Color(0xFFD26A00)
private val DeleteOrange = Color(0xFFD26A00)
private val ChartLabel = Color(0xFF7D7D84)
private val ChartMissingPoint = Color(0xFFB0B0B0)
private val PopupBackground = Color(0xF7FFFFFF)
