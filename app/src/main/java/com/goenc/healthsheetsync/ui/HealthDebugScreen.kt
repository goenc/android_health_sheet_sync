package com.goenc.healthsheetsync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.goenc.healthsheetsync.R
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthConnectAvailability
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun HealthDebugScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onShareCsvToDrive: () -> Unit,
    csvShareStatus: String?,
    onGoogleDriveLogin: () -> Unit,
    googleDriveStatus: String?,
    isGoogleDriveAuthorizing: Boolean,
    onUploadSpreadsheet: () -> Unit,
    spreadsheetUploadStatus: String?,
    isSpreadsheetUploading: Boolean,
    sharedText: String?,
    sharedTextImportStatus: String?,
    onSaveManualRecord: (ManualHealthRecordDraft) -> Unit,
    onInvalidateManualRecord: (String) -> Unit,
    onRestoreManualRecord: (String) -> Unit,
    onDeleteManualRecord: (String) -> Unit,
    onInvalidateStoredRecord: (String, String) -> Unit,
    onRestoreStoredRecord: (String, String) -> Unit,
    onDeleteStoredRecord: (String, String) -> Unit,
    basalMetabolicRate: Int,
    onSaveBasalMetabolicRate: (Int, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }
    var shouldEnableSettingsScroll by remember { mutableStateOf(false) }
    var currentLocalDate by remember { mutableStateOf(LocalDate.now(ZoneId.systemDefault())) }
    val shouldScrollRoot = showManualInput || (showSettings && shouldEnableSettingsScroll)

    LaunchedEffect(Unit) {
        while (true) {
            val zoneId = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zoneId)
            val nextDateStart = now.toLocalDate().plusDays(1).atStartOfDay(zoneId)
            delay(Duration.between(now, nextDateStart).toMillis().coerceAtLeast(1L))
            currentLocalDate = LocalDate.now(ZoneId.systemDefault())
        }
    }

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
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Column(
                modifier = Modifier.fillMaxSize().let { rootModifier ->
                    if (shouldScrollRoot) {
                        rootModifier.verticalScroll(rememberScrollState())
                    } else {
                        rootModifier
                    }
                },
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
                            onShareCsvToDrive = onShareCsvToDrive,
                            csvShareStatus = csvShareStatus,
                            onGoogleDriveLogin = onGoogleDriveLogin,
                            googleDriveStatus = googleDriveStatus,
                            isGoogleDriveAuthorizing = isGoogleDriveAuthorizing,
                            onUploadSpreadsheet = onUploadSpreadsheet,
                            spreadsheetUploadStatus = spreadsheetUploadStatus,
                            isSpreadsheetUploading = isSpreadsheetUploading,
                            onRecordListVisibilityChanged = { isVisible ->
                                shouldEnableSettingsScroll = isVisible
                            },
                            basalMetabolicRate = basalMetabolicRate,
                            onSaveBasalMetabolicRate = onSaveBasalMetabolicRate,
                            onBack = { showSettings = false },
                            dailyEnergySnapshots = state.dailyEnergySnapshots,
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
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings_24),
                            contentDescription = "設定",
                        )
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
                            targetDate = currentLocalDate,
                            glucoseRecords = state.glucoseRecords,
                            a1cDailyRecords = state.a1cDailyRecords,
                            manualRecords = state.manualRecords,
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 44.dp),
                        )

                        WeightTrendChart(
                            state.weightRecords,
                            state.stepDailyRecords,
                            state.dailyEnergySnapshots,
                            basalMetabolicRate,
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
}

@Composable
private fun MainSummaryValues(
    records: List<DebugWeightRecord>,
    dailySteps: List<DebugStepDaily>,
    targetDate: LocalDate,
    glucoseRecords: List<DebugGlucoseRecord>,
    a1cDailyRecords: List<DebugA1cDaily>,
    manualRecords: List<ManualHealthRecord>,
    modifier: Modifier = Modifier,
) {
    val latestRecord = records.maxByOrNull { it.measuredAt }
    val todaySteps = stepsForSummary(dailySteps, targetDate)
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
            SummaryValue("歩数", "${todaySteps}歩", ChartStepText)
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

internal fun stepsForSummary(
    dailySteps: List<DebugStepDaily>,
    targetDate: LocalDate,
): Long = dailySteps.firstOrNull { it.targetDate == targetDate }?.steps ?: 0L

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
