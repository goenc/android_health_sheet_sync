package com.goenc.healthsheetsync.ui

import android.graphics.Paint
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
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

    BackHandler(enabled = showSettings || showManualInput) {
        if (showManualInput) {
            showManualInput = false
            onRefresh()
        } else {
            showSettings = false
        }
    }

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
                .verticalScroll(rememberScrollState()),
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
                    .padding(start = 22.dp, top = 4.dp, end = 22.dp, bottom = 8.dp),
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
                        state.manualRecords,
                        onAddManualRecord = { showManualInput = true },
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
