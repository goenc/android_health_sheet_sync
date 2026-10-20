package com.goenc.healthsheetsync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.goenc.healthsheetsync.data.PfcBoardState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import com.goenc.healthsheetsync.R
import com.goenc.healthsheetsync.data.WeightMovingAverageMode
import com.goenc.healthsheetsync.data.ChartAxisSettingsStore
import com.goenc.healthsheetsync.data.LabResultsUiState
import com.goenc.healthsheetsync.data.LabResult
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.HealthConnectAvailability
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.ffmi
import com.goenc.healthsheetsync.health.navyMaleBodyFatPercent
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
    weightMovingAverageMode: WeightMovingAverageMode,
    onSaveWeightMovingAverageMode: (WeightMovingAverageMode, (String?) -> Unit) -> Unit,
    dailyBodySettings: List<DailyBodySetting>,
    onSaveDailyBodySetting: (Double, Int, (String?) -> Unit) -> Unit,
    pfcBoardState: PfcBoardState = PfcBoardState(),
    onRefreshPfcBoard: () -> Unit = {},
    onLoadPfcBoard: () -> Unit = {},
    labResultsState: LabResultsUiState = LabResultsUiState(),
    onRefreshLabResults: () -> Unit = {},
    onLoadLabResults: () -> Unit = {},
    onLabItemSettingsSaved: () -> Unit = {},
    onImportLabResults: (List<LabResult>, (String?) -> Unit) -> Unit = { _, onResult -> onResult("保存処理がありません") },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chartAxisStore = remember(context) { ChartAxisSettingsStore(context.applicationContext) }
    var chartAxisRanges by remember { mutableStateOf(chartAxisStore.load()) }
    var showSettings by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }
    var showPfcBoard by remember { mutableStateOf(false) }
    var showLabResults by remember { mutableStateOf(false) }
    var currentLocalDate by remember { mutableStateOf(LocalDate.now(ZoneId.systemDefault())) }
    var selectedChartRange by remember { mutableStateOf(WeightChartRange.TwoWeeks) }
    var selectedChartDate by remember { mutableStateOf<LocalDate?>(null) }
    val chartSelectionDate = selectedChartDate ?: latestChartTargetDate(state.weightRecords)
    val shouldScrollRoot = showManualInput || showSettings

    // 検査結果を開く前に取得を始め、保存済みの結果はそのまま表示に使う。
    LaunchedEffect(Unit) { onLoadLabResults(); onLoadPfcBoard() }

    val lastInteraction = remember { MutableStateFlow<Long?>(System.nanoTime()) }
    var mainScreenDisplayed by remember { mutableStateOf(false) }
    val initialPlanFiles = remember(pfcBoardState.boards, pfcBoardState.images) {
        initialPfcPlan(pfcBoardState.boards)?.meals.orEmpty().flatMap { meal ->
            meal.items.filter { it.enabled || (it.isMainDishCandidate && it.sourceEnabled) }
                .mapNotNull { it.imageId }
        }.mapNotNull { pfcBoardState.images[it.lowercase()] }.distinctBy { it.absolutePath }
    }
    var initialPlanPrepared by remember(initialPlanFiles) { mutableStateOf(false) }
    LaunchedEffect(mainScreenDisplayed, initialPlanFiles) {
        if (!mainScreenDisplayed) return@LaunchedEffect
        // Opening the menu must not cancel preparation of its default plan.
        PfcImageCache.prefetch(initialPlanFiles, initialPlan = true)
        initialPlanPrepared = true
    }
    val prefetchFiles = remember(pfcBoardState.boards, pfcBoardState.images) {
        pfcBoardState.boards.flatMap { board ->
            board.plans.sortedBy { it.id != board.baselineId }.flatMap { plan ->
                plan.meals.flatMap { meal -> meal.items.filter { it.sourceEnabled }.mapNotNull { it.imageId } }
            }
        }.mapNotNull { pfcBoardState.images[it.lowercase()] }.distinctBy { it.absolutePath }
    }
    LaunchedEffect(prefetchFiles, initialPlanPrepared) {
        if (!initialPlanPrepared) return@LaunchedEffect
        lastInteraction.collectLatest { releasedAt ->
            if (releasedAt == null) return@collectLatest
            delay(650)
            PfcImageCache.prefetch(prefetchFiles)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val zoneId = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zoneId)
            val nextDateStart = now.toLocalDate().plusDays(1).atStartOfDay(zoneId)
            delay(Duration.between(now, nextDateStart).toMillis().coerceAtLeast(1L))
            currentLocalDate = LocalDate.now(ZoneId.systemDefault())
        }
    }

    BackHandler(enabled = showSettings || showManualInput || showLabResults || showPfcBoard) {
        if (showPfcBoard) {
            showPfcBoard = false
        } else if (showLabResults) {
            showLabResults = false
        } else if (showManualInput) {
            showManualInput = false
            onRefresh()
        } else {
            showSettings = false
        }
    }

    if (!showPfcBoard && !showLabResults && state.isLoading && state.availability == HealthConnectAvailability.Checking) {
        LoadingScreen(modifier = modifier)
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .pointerInput(lastInteraction) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        lastInteraction.value = if (event.changes.any { it.pressed }) null else System.nanoTime()
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            if (showPfcBoard) {
                PfcBoardScreen(pfcBoardState, onRefreshPfcBoard, { showPfcBoard = false })
                return@CompositionLocalProvider
            }
            if (showLabResults) {
                LabResultsScreen(state = labResultsState, onRefresh = onRefreshLabResults,
                    onItemSettingsSaved = onLabItemSettingsSaved,
                    onImport = onImportLabResults, onBack = { showLabResults = false })
                return@CompositionLocalProvider
            }
            LaunchedEffect(state.isLoading, showSettings, showManualInput) {
                if (!state.isLoading && !showSettings && !showManualInput && !mainScreenDisplayed) {
                    // Wait until the loaded main screen has actually had a chance to draw.
                    withFrameNanos { }
                    withFrameNanos { }
                    mainScreenDisplayed = true
                }
            }
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
                            basalMetabolicRate = basalMetabolicRate,
                            onSaveBasalMetabolicRate = onSaveBasalMetabolicRate,
                            weightMovingAverageMode = weightMovingAverageMode,
                            onSaveWeightMovingAverageMode = onSaveWeightMovingAverageMode,
                            dailyBodySettings = dailyBodySettings,
                            onSaveDailyBodySetting = onSaveDailyBodySetting,
                            onBack = { showSettings = false },
                            dailyEnergySnapshots = state.dailyEnergySnapshots,
                            labResultsState = labResultsState,
                            chartAxisRanges = chartAxisRanges,
                            onSaveChartAxisRange = { metric, range ->
                                val saved = chartAxisStore.save(metric, range)
                                if (saved) chartAxisRanges = if (range == null) chartAxisRanges - metric else chartAxisRanges + (metric to range)
                                saved
                            },
                        )
                    }
                    return@Column
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 11.dp),
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
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        MainSummaryValues(
                            records = state.weightRecords,
                            dailySteps = state.stepDailyRecords,
                            targetDate = currentLocalDate,
                            glucoseRecords = state.glucoseRecords,
                            a1cDailyRecords = state.a1cDailyRecords,
                            manualRecords = state.manualRecords,
                            dailyBodySettings = state.dailyBodySettings,
                            modifier = Modifier
                                .padding(start = 8.dp, end = 28.dp)
                                .offset(y = 12.dp),
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            WeightTrendChart(
                                axisRanges = chartAxisRanges,
                                records = state.weightRecords,
                                dailySteps = state.stepDailyRecords,
                                dailyEnergySnapshots = state.dailyEnergySnapshots,
                                basalMetabolicRate = basalMetabolicRate,
                                weightMovingAverageMode = weightMovingAverageMode,
                                glucoseRecords = state.glucoseRecords,
                                a1cDailyRecords = state.a1cDailyRecords,
                                manualRecords = state.manualRecords,
                                dailyBodySettings = state.dailyBodySettings,
                                selectedRange = selectedChartRange,
                                onSelectedRangeChange = { selectedChartRange = it },
                                selectedDate = chartSelectionDate,
                                onSelectedDateChange = { selectedChartDate = it },
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
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MainDestinationButton(
                        label = "検査結果",
                        icon = R.drawable.ic_lab_results_24,
                        onClick = { showLabResults = true },
                        modifier = Modifier.weight(1f),
                    )
                    MainDestinationButton(
                        label = "メニュー",
                        icon = R.drawable.ic_restaurant_24,
                        onClick = { showPfcBoard = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MainDestinationButton(label: String, icon: Int, onClick: () -> Unit, modifier: Modifier) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 38.dp) {
        Button(
            onClick = onClick,
            modifier = modifier.height(38.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = Color(0xFFCEDBEF), modifier = Modifier.size(20.dp))
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
    dailyBodySettings: List<DailyBodySetting>,
    modifier: Modifier = Modifier,
) {
    val latestRecord = records.maxByOrNull { it.measuredAt }
    val bmi = bmiForSummary(records, dailyBodySettings)
    val bodyFat = navyBodyFatForSummary(manualRecords, dailyBodySettings)
    val ffmiValue = ffmiForSummary(records, manualRecords, dailyBodySettings)
    val todayStepRecord = dailySteps.firstOrNull { it.targetDate == targetDate }
    val todaySteps = todayStepRecord?.steps ?: 0L
    val latestFastingGlucose = glucoseRecords.fastingGlucoseRecords().firstOrNull()
    val latestA1c = a1cDailyRecords.maxByOrNull { it.targetDate }
    val latestWaist = manualRecords.latestManualValue(ManualRecordType.Waist)
    val averageBloodPressure = manualRecords.latestDailyBloodPressureAverageText()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryValue("体重", latestRecord?.let { "${formatDecimal(it.weightKg)}kg" } ?: "-", ChartBlue)
            SummaryValue("歩数", "${todaySteps}歩", ChartStepText)
            SummaryValue("距離", formatDistanceMeters(todayStepRecord?.distanceMeters), ChartStepText)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryValue(
                "血糖",
                latestFastingGlucose?.let { "${formatDecimal(it.bloodGlucoseMgDl)}" } ?: "-",
                ChartGlucose,
            )
            SummaryValue("A1c", latestA1c?.let { "${formatDecimal(it.a1cPercent)}%" } ?: "-", ChartA1c)
            SummaryValue("血圧", averageBloodPressure ?: "-", AppMutedBlue)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryValue("腹囲", latestWaist ?: "-", ChartSummary)
            bmi?.let { value ->
                SummaryValue("BMI", formatDecimal(value), ChartSummary)
            }
            if (bodyFat != null && ffmiValue != null) {
                SummaryValue("FFMI", formatFfmiValue(ffmiValue, bodyFat), ChartSummary)
            }
        }
    }
}

internal fun stepsForSummary(
    dailySteps: List<DebugStepDaily>,
    targetDate: LocalDate,
): Long = dailySteps.firstOrNull { it.targetDate == targetDate }?.steps ?: 0L

internal fun bmiForSummary(
    weightRecords: List<DebugWeightRecord>,
    dailyBodySettings: List<DailyBodySetting>,
): Double? {
    val morningWeight = weightRecords
        .filter { it.timeBand == "朝" }
        .maxByOrNull { it.measuredAt }
        ?.weightKg
        ?: return null
    val heightCm = dailyBodySettings
        .maxWithOrNull(compareBy<DailyBodySetting> { it.targetDate }.thenBy { it.updatedAt })
        ?.heightCm
        ?: return null
    if (!morningWeight.isFinite() || morningWeight <= 0.0 || !heightCm.isFinite() || heightCm <= 0.0) {
        return null
    }
    val heightM = heightCm / 100.0
    return (morningWeight / (heightM * heightM)).takeIf { it.isFinite() && it > 0.0 }
}

internal fun navyBodyFatForSummary(
    manualRecords: List<ManualHealthRecord>,
    dailyBodySettings: List<DailyBodySetting>,
): Double? {
    val waistCm = manualRecords.latestManualValue(ManualRecordType.Waist)
        ?.removeSuffix(" cm")
        ?.trim()
        ?.toDoubleOrNull()
        ?: return null
    val neckCm = manualRecords.latestManualValue(ManualRecordType.Neck)
        ?.removeSuffix(" cm")
        ?.trim()
        ?.toDoubleOrNull()
        ?: return null
    val heightCm = dailyBodySettings
        .maxWithOrNull(compareBy<DailyBodySetting> { it.targetDate }.thenBy { it.updatedAt })
        ?.heightCm
        ?: return null
    return navyMaleBodyFatPercent(
        heightCm = heightCm,
        waistCm = waistCm,
        neckCm = neckCm,
    )
}

internal fun ffmiForSummary(
    weightRecords: List<DebugWeightRecord>,
    manualRecords: List<ManualHealthRecord>,
    dailyBodySettings: List<DailyBodySetting>,
): Double? {
    val weightKg = weightRecords.maxByOrNull { it.measuredAt }?.weightKg ?: return null
    val bodyFatPercent = navyBodyFatForSummary(manualRecords, dailyBodySettings) ?: return null
    val heightCm = dailyBodySettings
        .maxWithOrNull(compareBy<DailyBodySetting> { it.targetDate }.thenBy { it.updatedAt })
        ?.heightCm
        ?: return null
    return ffmi(
        weightKg = weightKg,
        heightCm = heightCm,
        bodyFatPercent = bodyFatPercent,
    )
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
        fontSize = 13.sp * 1.05f * 1.10f,
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
