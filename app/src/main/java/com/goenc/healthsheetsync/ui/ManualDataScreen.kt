package com.goenc.healthsheetsync.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.InvalidatedGraphRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import kotlinx.coroutines.withTimeoutOrNull

private val manualRecordTypeDisplayOrder = listOf(
    ManualRecordType.BloodPressure,
    ManualRecordType.Waist,
    ManualRecordType.Neck,
    ManualRecordType.BloodGlucose,
    ManualRecordType.Weight,
    ManualRecordType.A1c,
    ManualRecordType.Steps,
)

@Composable
internal fun ManualDataScreen(
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
    var selectedType by remember { mutableStateOf(ManualRecordType.BloodPressure) }
    var lastSavedTypeLabel by remember { mutableStateOf(ManualRecordType.BloodPressure.label) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedTimeBand by remember { mutableStateOf(selectedType.defaultManualTimeBand()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showSaveCompleteDialog by remember { mutableStateOf(false) }
    var primaryValue by remember { mutableStateOf("") }
    var secondaryValue by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    val labels = selectedType.inputLabels()
    val selectedRecords = remember(selectedType, weightRecords, dailySteps, glucoseRecords, manualRecords, invalidatedRecords) {
        selectedType.toGraphDataItems(
            weightRecords = weightRecords,
            dailySteps = dailySteps,
            glucoseRecords = glucoseRecords,
            manualRecords = manualRecords,
            invalidatedRecords = invalidatedRecords,
            limit = MANUAL_LIST_RECENT_LIMIT,
        )
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
        manualRecordTypeDisplayOrder.chunked(3).forEach { rowTypes ->
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
                    lastSavedTypeLabel = selectedType.label
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
                    showSaveCompleteDialog = true
                }
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
        ) {
            Text("保存")
        }
    }

    if (showSaveCompleteDialog) {
        AlertDialog(
            onDismissRequest = { showSaveCompleteDialog = false },
            title = {
                Text(
                    text = "${lastSavedTypeLabel}の登録完了",
                    color = AppPrimary,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "${lastSavedTypeLabel}のデータを登録しました。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppText,
                )
            },
            confirmButton = {
                Button(
                    onClick = { showSaveCompleteDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimary),
                ) {
                    Text("OK")
                }
            },
        )
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
