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
import com.goenc.healthsheetsync.health.HealthConnectDebugReader
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.ui.HealthDebugScreen
import com.goenc.healthsheetsync.ui.theme.HealthSheetSyncTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var healthReader: HealthConnectDebugReader
    private var healthState by mutableStateOf(HealthDebugUiState())
    private var externalSaveStatus by mutableStateOf<String?>(null)
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
}

private const val TAG = "HealthSheetSync"
private const val WORKBOOK_ASSET_NAME = "health_sheet_sync_work_branch_template.xlsx"
private const val DEFAULT_WORKBOOK_NAME = "health_sheet_sync.xlsx"
private const val WORKBOOK_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
