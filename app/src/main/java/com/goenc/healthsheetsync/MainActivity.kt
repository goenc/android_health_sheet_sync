package com.goenc.healthsheetsync

import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.goenc.healthsheetsync.data.SpreadsheetUploadSettings
import com.goenc.healthsheetsync.data.SpreadsheetUploadResult
import com.goenc.healthsheetsync.data.SpreadsheetUploader
import com.goenc.healthsheetsync.health.HealthConnectDebugReader
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.ui.HealthDebugScreen
import com.goenc.healthsheetsync.ui.theme.HealthSheetSyncTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var healthReader: HealthConnectDebugReader
    private lateinit var spreadsheetSettings: SpreadsheetUploadSettings
    private val spreadsheetUploader = SpreadsheetUploader()
    private var healthState by mutableStateOf(HealthDebugUiState())
    private var externalSaveStatus by mutableStateOf<String?>(null)
    private var spreadsheetWebAppUrl by mutableStateOf("")
    private var spreadsheetUploadStatus by mutableStateOf<String?>(null)
    private var isSpreadsheetUploading by mutableStateOf(false)
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
    private val createExternalWorkbook = registerForActivityResult(
        ActivityResultContracts.CreateDocument(WORKBOOK_MIME_TYPE),
    ) { uri ->
        if (uri == null) {
            externalSaveStatus = "外部保存をキャンセルしました"
            return@registerForActivityResult
        }

        externalSaveStatus = runCatching {
            assets.open(WORKBOOK_ASSET_NAME).use { input ->
                contentResolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                } ?: error("保存先を開けませんでした")
            }
            "外部保存が完了しました"
        }.getOrElse { error ->
            Log.e(TAG, "Failed to save workbook template externally.", error)
            "外部保存に失敗しました: ${error.message ?: "原因不明"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthReader = HealthConnectDebugReader(applicationContext)
        spreadsheetSettings = SpreadsheetUploadSettings(applicationContext)
        spreadsheetWebAppUrl = spreadsheetSettings.webAppUrl
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
                        onSaveExternalWorkbook = {
                            externalSaveStatus = null
                            createExternalWorkbook.launch(DEFAULT_WORKBOOK_NAME)
                        },
                        externalSaveStatus = externalSaveStatus,
                        onUploadSpreadsheet = { uploadSpreadsheetData() },
                        spreadsheetUploadStatus = spreadsheetUploadStatus,
                        isSpreadsheetUploading = isSpreadsheetUploading,
                        spreadsheetWebAppUrl = spreadsheetWebAppUrl,
                        targetSpreadsheetUrl = SpreadsheetUploadSettings.TARGET_SPREADSHEET_URL,
                        onSpreadsheetWebAppUrlChange = { url ->
                            spreadsheetWebAppUrl = url
                            spreadsheetSettings.webAppUrl = url
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
        refreshHealthData()
    }

    private fun refreshHealthData() {
        lifecycleScope.launch {
            healthState = healthState.copy(isLoading = true)
            healthState = healthReader.load()
        }
    }

    private fun uploadSpreadsheetData() {
        val webAppUrl = spreadsheetWebAppUrl.trim()
        if (webAppUrl.isBlank()) {
            spreadsheetUploadStatus = "設定画面でWebアプリURLを入力してください"
            return
        }

        lifecycleScope.launch {
            isSpreadsheetUploading = true
            spreadsheetUploadStatus = "アップロード中"
            spreadsheetUploadStatus = when (
                val result = spreadsheetUploader.upload(
                    state = healthState,
                    webAppUrl = webAppUrl,
                )
            ) {
                is SpreadsheetUploadResult.Success ->
                    "アップロード完了: 体重${result.weightCount}件、血糖${result.glucoseCount}件、歩数${result.stepCount}件"
                is SpreadsheetUploadResult.Failure ->
                    "アップロード失敗: ${result.message}"
            }
            isSpreadsheetUploading = false
        }
    }
}

private const val TAG = "HealthSheetSync"
private const val WORKBOOK_ASSET_NAME = "health_sheet_sync_work_branch_template.xlsx"
private const val DEFAULT_WORKBOOK_NAME = "health_sheet_sync.xlsx"
private const val WORKBOOK_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
