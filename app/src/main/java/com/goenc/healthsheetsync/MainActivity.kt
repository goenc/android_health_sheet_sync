package com.goenc.healthsheetsync

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import com.goenc.healthsheetsync.data.BasalMetabolicRateSettingsStore
import com.goenc.healthsheetsync.data.StoredHealthData
import com.goenc.healthsheetsync.data.WeightMovingAverageMode
import com.goenc.healthsheetsync.data.WeightMovingAverageSettingsStore
import com.goenc.healthsheetsync.export.HealthCsvShareExporter
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthConnectDebugReader
import com.goenc.healthsheetsync.health.DailyEnergyCalculator
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.PermissionState
import com.goenc.healthsheetsync.share.SharedTextImporter
import com.goenc.healthsheetsync.sync.SpreadsheetSyncCoordinator
import com.goenc.healthsheetsync.sync.SpreadsheetSyncUiState
import com.goenc.healthsheetsync.ui.HealthDebugScreen
import com.goenc.healthsheetsync.ui.theme.HealthSheetSyncTheme
import com.goenc.healthsheetsync.widget.HealthGraphWidgetUpdater
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var healthReader: HealthConnectDebugReader
    private lateinit var localStore: LocalHealthDataStore
    private lateinit var basalMetabolicRateSettingsStore: BasalMetabolicRateSettingsStore
    private lateinit var weightMovingAverageSettingsStore: WeightMovingAverageSettingsStore
    private lateinit var sharedTextImporter: SharedTextImporter
    private lateinit var healthCsvShareExporter: HealthCsvShareExporter
    private lateinit var spreadsheetSyncCoordinator: SpreadsheetSyncCoordinator
    private var healthState by mutableStateOf(HealthDebugUiState())
    private var basalMetabolicRate by mutableStateOf(DailyEnergyCalculator.DEFAULT_BASAL_METABOLIC_RATE)
    private var weightMovingAverageMode by mutableStateOf(WeightMovingAverageMode.All)
    private var csvShareStatus by mutableStateOf<String?>(null)
    private var spreadsheetSyncState by mutableStateOf(SpreadsheetSyncUiState())
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedTextImportStatus by mutableStateOf<String?>(null)
    private var healthRefreshJob: Job? = null
    private var localRefreshJob: Job? = null
    private var hasCompletedInitialHealthLoad = false
    private val healthPermissionRequestPreferences by lazy {
        getSharedPreferences(HEALTH_PERMISSION_REQUEST_PREFERENCES, MODE_PRIVATE)
    }
    private val requestPermissions = registerForActivityResult(
        HealthConnectDebugReader.permissionRequestContract(),
    ) { grantedPermissions ->
        Log.d(
            TAG,
            "Health Connect permission request result: ${
                grantedPermissions.sorted().joinToString()
            }",
        )
        refreshHealthData()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthReader = HealthConnectDebugReader(applicationContext)
        localStore = LocalHealthDataStore(applicationContext)
        basalMetabolicRateSettingsStore = BasalMetabolicRateSettingsStore(applicationContext)
        basalMetabolicRate = basalMetabolicRateSettingsStore.load()
        weightMovingAverageSettingsStore = WeightMovingAverageSettingsStore(applicationContext)
        weightMovingAverageMode = weightMovingAverageSettingsStore.load()
        sharedTextImporter = SharedTextImporter(applicationContext, localStore)
        healthCsvShareExporter = HealthCsvShareExporter(applicationContext)
        spreadsheetSyncCoordinator = SpreadsheetSyncCoordinator(
            activity = this,
            scope = lifecycleScope,
            localStore = localStore,
            onStateChanged = { spreadsheetSyncState = it },
        )
        handleSharedText(intent)
        enableEdgeToEdge()
        setContent {
            HealthSheetSyncTheme {
                Scaffold { innerPadding ->
                    val layoutDirection = LocalLayoutDirection.current
                    val contentPadding = PaddingValues(
                        start = innerPadding.calculateLeftPadding(layoutDirection),
                        top = innerPadding.calculateTopPadding(),
                        end = innerPadding.calculateRightPadding(layoutDirection),
                        bottom = (innerPadding.calculateBottomPadding() - 20.dp).coerceAtLeast(0.dp),
                    )
                    HealthDebugScreen(
                        state = healthState,
                        onRequestPermissions = {
                            Log.d(
                                TAG,
                                "Launching Health Connect permission request: ${
                                    HealthConnectDebugReader.REQUIRED_PERMISSIONS.sorted().joinToString()
                                }",
                            )
                            requestPermissions.launch(HealthConnectDebugReader.REQUIRED_PERMISSIONS)
                        },
                        onRefresh = { requestMissingHealthPermissionsOrRefresh() },
                        onShareCsvToDrive = { shareCsvToDrive() },
                        csvShareStatus = csvShareStatus,
                        onGoogleDriveLogin = { spreadsheetSyncCoordinator.authorizeGoogleDrive() },
                        googleDriveStatus = spreadsheetSyncState.googleDriveStatus,
                        isGoogleDriveAuthorizing = spreadsheetSyncState.isGoogleDriveAuthorizing,
                        onUploadSpreadsheet = {
                            spreadsheetSyncCoordinator.uploadSpreadsheetData(healthState.isLoading)
                        },
                        spreadsheetUploadStatus = spreadsheetSyncState.spreadsheetUploadStatus,
                        isSpreadsheetUploading = spreadsheetSyncState.isSpreadsheetUploading,
                        sharedText = sharedText,
                        sharedTextImportStatus = sharedTextImportStatus,
                        onSaveManualRecord = { draft -> saveManualRecord(draft) },
                        onInvalidateManualRecord = { id -> invalidateManualRecord(id) },
                        onRestoreManualRecord = { id -> restoreManualRecord(id) },
                        onDeleteManualRecord = { id -> deleteManualRecord(id) },
                        onInvalidateStoredRecord = { recordType, uniqueKey ->
                            invalidateStoredRecord(recordType, uniqueKey)
                        },
                        onRestoreStoredRecord = { recordType, uniqueKey ->
                            restoreStoredRecord(recordType, uniqueKey)
                        },
                        onDeleteStoredRecord = { recordType, uniqueKey ->
                            deleteStoredRecord(recordType, uniqueKey)
                        },
                        basalMetabolicRate = basalMetabolicRate,
                        onSaveBasalMetabolicRate = { value, onResult ->
                            saveBasalMetabolicRate(value, onResult)
                        },
                        weightMovingAverageMode = weightMovingAverageMode,
                        onSaveWeightMovingAverageMode = { mode, onResult ->
                            saveWeightMovingAverageMode(mode, onResult)
                        },
                        dailyBodySettings = healthState.dailyBodySettings,
                        onSaveDailyBodySetting = { heightCm, averageIntakeKcal, onResult ->
                            saveDailyBodySetting(heightCm, averageIntakeKcal, onResult)
                        },
                        modifier = Modifier.padding(contentPadding),
                    )
                }
            }
        }
        refreshHealthData()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText(intent)
    }

    private fun handleSharedText(intent: Intent?) {
        val result = sharedTextImporter.importFrom(intent) ?: return
        sharedText = result.text
        sharedTextImportStatus = result.status
        if (result.imported) {
            refreshLocalHealthData()
            spreadsheetSyncCoordinator.requestImmediateAutomaticSync()
        }
    }

    private fun refreshHealthData() {
        if (healthRefreshJob?.isActive == true) return
        healthRefreshJob = lifecycleScope.launch {
            healthState = healthState.copy(isLoading = true)
            val previousState = healthState
            val storedData = withContext(Dispatchers.IO) {
                localStore.load()
            }
            healthState = healthState.withStoredData(storedData)
            val loadedState = withContext(Dispatchers.IO) {
                healthReader.load(basalMetabolicRate)
            }
            healthState = loadedState
            requestMissingHealthPermissionsAutomatically(loadedState.permissions)
            val shouldSync = !hasCompletedInitialHealthLoad ||
                (loadedState.permissions is PermissionState.Granted &&
                    previousState.hasMirrorDataChanged(loadedState))
            hasCompletedInitialHealthLoad = true
            if (shouldSync) {
                spreadsheetSyncCoordinator.scheduleAutomaticSync()
            }
        }
    }

    private fun requestMissingHealthPermissionsOrRefresh() {
        val missingPermissions = healthState.permissions.missingPermissions()
        if (missingPermissions.isEmpty()) {
            refreshHealthData()
            return
        }
        requestPermissions.launch(missingPermissions)
    }

    private fun requestMissingHealthPermissionsAutomatically(permissionState: PermissionState) {
        val missingPermissions = permissionState.missingPermissions()
        if (missingPermissions.isEmpty()) return

        val requestSignature = HealthConnectDebugReader.REQUIRED_PERMISSIONS
            .sorted()
            .joinToString("|")
        val previousSignature = healthPermissionRequestPreferences.getString(
            HEALTH_PERMISSION_REQUEST_SIGNATURE_KEY,
            null,
        )
        if (previousSignature == requestSignature) return

        healthPermissionRequestPreferences.edit()
            .putString(HEALTH_PERMISSION_REQUEST_SIGNATURE_KEY, requestSignature)
            .apply()
        Log.d(
            TAG,
            "Automatically requesting missing Health Connect permissions: ${
                missingPermissions.sorted().joinToString()
            }",
        )
        requestPermissions.launch(missingPermissions)
    }

    private fun refreshLocalHealthData() {
        if (localRefreshJob?.isActive == true) return
        localRefreshJob = lifecycleScope.launch {
            val storedData = withContext(Dispatchers.IO) {
                localStore.load()
            }
            healthState = healthState.withStoredData(storedData)
            HealthGraphWidgetUpdater.requestUpdate(applicationContext)
        }
    }

    private fun saveManualRecord(draft: ManualHealthRecordDraft) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    localStore.saveManualRecord(draft)
                    true
                }.getOrDefault(false)
            }
            if (saved && draft.type == ManualRecordType.Steps) {
                val steps = draft.valueText.removeSuffix("歩").toLongOrNull()
                if (steps != null) {
                    withContext(Dispatchers.IO) {
                        localStore.finalizeManualStepsForDate(
                            targetDate = draft.measuredAt.toLocalDate(),
                            steps = steps,
                            basalMetabolicRate = basalMetabolicRate,
                        )
                    }
                }
            }
            refreshLocalHealthData()
            if (saved) {
                spreadsheetSyncCoordinator.requestImmediateAutomaticSync()
            }
        }
    }

    private fun invalidateManualRecord(id: String) {
        updateLocalDataAndSync { invalidateManualRecord(id) }
    }

    private fun restoreManualRecord(id: String) {
        updateLocalDataAndSync { restoreManualRecord(id) }
    }

    private fun deleteManualRecord(id: String) {
        updateLocalDataAndSync { deleteManualRecord(id) }
    }

    private fun invalidateStoredRecord(recordType: String, uniqueKey: String) {
        updateLocalDataAndSync { invalidateStoredRecord(recordType, uniqueKey) }
    }

    private fun restoreStoredRecord(recordType: String, uniqueKey: String) {
        updateLocalDataAndSync { restoreStoredRecord(recordType, uniqueKey) }
    }

    private fun deleteStoredRecord(recordType: String, uniqueKey: String) {
        updateLocalDataAndSync { deleteStoredRecord(recordType, uniqueKey) }
    }

    private fun updateLocalDataAndSync(update: LocalHealthDataStore.() -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                localStore.update()
            }
            refreshLocalHealthData()
            spreadsheetSyncCoordinator.requestImmediateAutomaticSync()
        }
    }

    private fun saveBasalMetabolicRate(value: Int, onResult: (String?) -> Unit) {
        val previousBasalMetabolicRate = basalMetabolicRate
        lifecycleScope.launch {
            val errorMessage = withContext(Dispatchers.IO) {
                runCatching {
                    if (!localStore.finalizeManualOnlyPastDaysIfSafe(previousBasalMetabolicRate)) {
                        return@runCatching UNSYNCED_PAST_STEPS_MESSAGE
                    }
                    check(basalMetabolicRateSettingsStore.save(value))
                    null
                }.getOrElse { "基礎代謝量を保存できませんでした" }
            }
            if (errorMessage == null) {
                basalMetabolicRate = value
                onResult(null)
                refreshLocalHealthData()
            } else {
                onResult(errorMessage)
            }
        }
    }

    private fun saveWeightMovingAverageMode(
        mode: WeightMovingAverageMode,
        onResult: (String?) -> Unit,
    ) {
        lifecycleScope.launch {
            val errorMessage = withContext(Dispatchers.IO) {
                runCatching {
                    check(weightMovingAverageSettingsStore.save(mode))
                    null
                }.getOrElse { "7日移動平均の設定を保存できませんでした" }
            }
            if (errorMessage == null) {
                weightMovingAverageMode = mode
            }
            onResult(errorMessage)
        }
    }

    private fun saveDailyBodySetting(
        heightCm: Double,
        averageIntakeKcal: Int,
        onResult: (String?) -> Unit,
    ) {
        lifecycleScope.launch {
            val errorMessage = withContext(Dispatchers.IO) {
                runCatching {
                    check(
                        localStore.saveDailyBodySetting(
                            targetDate = LocalDate.now(ZoneId.systemDefault()),
                            heightCm = heightCm,
                            averageIntakeKcal = averageIntakeKcal,
                        ),
                    )
                    null
                }.getOrElse { "身長・平均摂取カロリーを保存できませんでした" }
            }
            if (errorMessage == null) {
                onResult(null)
                refreshLocalHealthData()
                spreadsheetSyncCoordinator.requestImmediateAutomaticSync()
            } else {
                onResult(errorMessage)
            }
        }
    }

    private fun shareCsvToDrive() {
        csvShareStatus = null
        val csvFile = runCatching {
            healthCsvShareExporter.export(healthState)
        }.getOrElse { error ->
            csvShareStatus = "CSV作成に失敗しました: ${error.message ?: "原因不明"}"
            return
        }
        val csvUri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", csvFile)
        }.getOrElse { error ->
            csvShareStatus = "CSV作成に失敗しました: ${error.message ?: "原因不明"}"
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = HealthCsvShareExporter.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, csvUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, "Drive保存"))
        }.onSuccess {
            csvShareStatus = "CSVを共有できます。保存先にGoogle Driveを選択してください"
        }.onFailure { error ->
            csvShareStatus = "共有先を開けませんでした: ${error.message ?: "原因不明"}"
        }
    }

}

private fun HealthDebugUiState.withStoredData(storedData: StoredHealthData): HealthDebugUiState {
    val yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1)
    return copy(
        isLoading = false,
        weightRecords = storedData.weightRecords,
        glucoseRecords = storedData.glucoseRecords,
        stepDailyRecords = storedData.stepDailyRecords,
        a1cDailyRecords = storedData.a1cDailyRecords,
        manualRecords = storedData.manualRecords,
        invalidatedGraphRecords = storedData.invalidatedGraphRecords,
        dailyEnergySnapshots = storedData.dailyEnergySnapshots,
        dailyBodySettings = storedData.dailyBodySettings,
        yesterdaySteps = storedData.stepDailyRecords.firstOrNull { it.targetDate == yesterday },
        sourceSummaries = buildSourceSummaries(storedData.weightRecords, storedData.glucoseRecords),
    )
}

private fun HealthDebugUiState.hasMirrorDataChanged(other: HealthDebugUiState): Boolean {
    return weightRecords != other.weightRecords ||
        glucoseRecords != other.glucoseRecords ||
        stepDailyRecords != other.stepDailyRecords ||
        a1cDailyRecords != other.a1cDailyRecords ||
        manualRecords != other.manualRecords ||
        dailyBodySettings != other.dailyBodySettings
}

private fun buildSourceSummaries(
    weightRecords: List<DebugWeightRecord>,
    glucoseRecords: List<DebugGlucoseRecord>,
): List<String> {
    val sources = weightRecords.map { it.sourceAppName to it.sourcePackageName } +
        glucoseRecords.map { it.sourceAppName to it.sourcePackageName }
    return sources.distinct().map { (name, packageName) ->
        "$name / $packageName"
    }.ifEmpty {
        listOf("$UNKNOWN / $UNKNOWN")
    }
}

private fun PermissionState.missingPermissions(): Set<String> {
    return (this as? PermissionState.Missing)?.missingPermissions?.toSet().orEmpty()
}

private const val TAG = "HealthSheetSync"
private const val UNKNOWN = "不明"
private const val HEALTH_PERMISSION_REQUEST_PREFERENCES = "health_permission_request"
private const val HEALTH_PERMISSION_REQUEST_SIGNATURE_KEY = "automatically_requested_signature"
private const val UNSYNCED_PAST_STEPS_MESSAGE = "未同期の過去日の歩数があります。先にHealth Connectを更新してください"
