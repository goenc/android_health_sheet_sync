package com.goenc.healthsheetsync

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import com.goenc.healthsheetsync.data.BasalMetabolicRateSettingsStore
import com.goenc.healthsheetsync.data.StoredHealthData
import com.goenc.healthsheetsync.export.HealthCsvShareExporter
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthConnectDebugReader
import com.goenc.healthsheetsync.health.DailyEnergyCalculator
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.share.SharedTextImporter
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
    private lateinit var sharedTextImporter: SharedTextImporter
    private lateinit var healthCsvShareExporter: HealthCsvShareExporter
    private var healthState by mutableStateOf(HealthDebugUiState())
    private var basalMetabolicRate by mutableStateOf(DailyEnergyCalculator.DEFAULT_BASAL_METABOLIC_RATE)
    private var csvShareStatus by mutableStateOf<String?>(null)
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedTextImportStatus by mutableStateOf<String?>(null)
    private var healthRefreshJob: Job? = null
    private var localRefreshJob: Job? = null
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
        sharedTextImporter = SharedTextImporter(applicationContext, localStore)
        healthCsvShareExporter = HealthCsvShareExporter(applicationContext)
        handleSharedText(intent)
        enableEdgeToEdge()
        setContent {
            HealthSheetSyncTheme {
                Scaffold { innerPadding ->
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
                        onRefresh = { refreshHealthData() },
                        onShareCsvToDrive = { shareCsvToDrive() },
                        csvShareStatus = csvShareStatus,
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
                        modifier = Modifier.padding(innerPadding),
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
        if (result.imported) refreshLocalHealthData()
    }

    private fun refreshHealthData() {
        if (healthRefreshJob?.isActive == true) return
        healthRefreshJob = lifecycleScope.launch {
            healthState = healthState.copy(isLoading = true)
            val storedData = withContext(Dispatchers.IO) {
                localStore.finalizePastDailyEnergySnapshots(basalMetabolicRate)
                localStore.load()
            }
            healthState = healthState.withStoredData(storedData)
            healthState = withContext(Dispatchers.IO) {
                healthReader.load(basalMetabolicRate)
            }
        }
    }

    private fun refreshLocalHealthData() {
        if (localRefreshJob?.isActive == true) return
        localRefreshJob = lifecycleScope.launch {
            val storedData = withContext(Dispatchers.IO) {
                localStore.finalizePastDailyEnergySnapshots(basalMetabolicRate)
                localStore.load()
            }
            healthState = healthState.withStoredData(storedData)
            HealthGraphWidgetUpdater.requestUpdate(applicationContext)
        }
    }

    private fun saveManualRecord(draft: ManualHealthRecordDraft) {
        localStore.saveManualRecord(draft)
        refreshLocalHealthData()
    }

    private fun invalidateManualRecord(id: String) {
        localStore.invalidateManualRecord(id)
        refreshLocalHealthData()
    }

    private fun restoreManualRecord(id: String) {
        localStore.restoreManualRecord(id)
        refreshLocalHealthData()
    }

    private fun deleteManualRecord(id: String) {
        localStore.deleteManualRecord(id)
        refreshLocalHealthData()
    }

    private fun invalidateStoredRecord(recordType: String, uniqueKey: String) {
        localStore.invalidateStoredRecord(recordType, uniqueKey)
        refreshLocalHealthData()
    }

    private fun restoreStoredRecord(recordType: String, uniqueKey: String) {
        localStore.restoreStoredRecord(recordType, uniqueKey)
        refreshLocalHealthData()
    }

    private fun deleteStoredRecord(recordType: String, uniqueKey: String) {
        localStore.deleteStoredRecord(recordType, uniqueKey)
        refreshLocalHealthData()
    }

    private fun saveBasalMetabolicRate(value: Int, onResult: (Boolean) -> Unit) {
        val previousBasalMetabolicRate = basalMetabolicRate
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    localStore.finalizePastDailyEnergySnapshots(previousBasalMetabolicRate)
                    check(basalMetabolicRateSettingsStore.save(value))
                }.isSuccess
            }
            if (saved) {
                basalMetabolicRate = value
                onResult(true)
                refreshLocalHealthData()
            } else {
                onResult(false)
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
        yesterdaySteps = storedData.stepDailyRecords.firstOrNull { it.targetDate == yesterday },
        sourceSummaries = buildSourceSummaries(storedData.weightRecords, storedData.glucoseRecords),
    )
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

private const val TAG = "HealthSheetSync"
private const val UNKNOWN = "不明"
