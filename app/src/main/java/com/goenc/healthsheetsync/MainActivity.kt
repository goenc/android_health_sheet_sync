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
import com.goenc.healthsheetsync.export.HealthCsvShareExporter
import com.goenc.healthsheetsync.health.HealthConnectDebugReader
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.share.SharedTextImporter
import com.goenc.healthsheetsync.ui.HealthDebugScreen
import com.goenc.healthsheetsync.ui.theme.HealthSheetSyncTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var healthReader: HealthConnectDebugReader
    private lateinit var localStore: LocalHealthDataStore
    private lateinit var sharedTextImporter: SharedTextImporter
    private lateinit var healthCsvShareExporter: HealthCsvShareExporter
    private var healthState by mutableStateOf(HealthDebugUiState())
    private var csvShareStatus by mutableStateOf<String?>(null)
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedTextImportStatus by mutableStateOf<String?>(null)
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
        if (result.imported) refreshHealthData()
    }

    private fun refreshHealthData() {
        lifecycleScope.launch {
            healthState = healthState.copy(isLoading = true)
            healthState = healthReader.load()
        }
    }

    private fun saveManualRecord(draft: ManualHealthRecordDraft) {
        localStore.saveManualRecord(draft)
        refreshHealthData()
    }

    private fun invalidateManualRecord(id: String) {
        localStore.invalidateManualRecord(id)
        refreshHealthData()
    }

    private fun restoreManualRecord(id: String) {
        localStore.restoreManualRecord(id)
        refreshHealthData()
    }

    private fun deleteManualRecord(id: String) {
        localStore.deleteManualRecord(id)
        refreshHealthData()
    }

    private fun invalidateStoredRecord(recordType: String, uniqueKey: String) {
        localStore.invalidateStoredRecord(recordType, uniqueKey)
        refreshHealthData()
    }

    private fun restoreStoredRecord(recordType: String, uniqueKey: String) {
        localStore.restoreStoredRecord(recordType, uniqueKey)
        refreshHealthData()
    }

    private fun deleteStoredRecord(recordType: String, uniqueKey: String) {
        localStore.deleteStoredRecord(recordType, uniqueKey)
        refreshHealthData()
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

private const val TAG = "HealthSheetSync"
