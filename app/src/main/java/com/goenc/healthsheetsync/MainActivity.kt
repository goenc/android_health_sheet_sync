package com.goenc.healthsheetsync

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import com.goenc.healthsheetsync.data.BasalMetabolicRateSettingsStore
import com.goenc.healthsheetsync.data.SpreadsheetUploadResult
import com.goenc.healthsheetsync.data.SpreadsheetUploader
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
import com.goenc.healthsheetsync.ui.HealthDebugScreen
import com.goenc.healthsheetsync.ui.theme.HealthSheetSyncTheme
import com.goenc.healthsheetsync.widget.HealthGraphWidgetUpdater
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var healthReader: HealthConnectDebugReader
    private lateinit var localStore: LocalHealthDataStore
    private lateinit var basalMetabolicRateSettingsStore: BasalMetabolicRateSettingsStore
    private lateinit var weightMovingAverageSettingsStore: WeightMovingAverageSettingsStore
    private lateinit var sharedTextImporter: SharedTextImporter
    private lateinit var healthCsvShareExporter: HealthCsvShareExporter
    private val spreadsheetUploader = SpreadsheetUploader()
    private var healthState by mutableStateOf(HealthDebugUiState())
    private var basalMetabolicRate by mutableStateOf(DailyEnergyCalculator.DEFAULT_BASAL_METABOLIC_RATE)
    private var weightMovingAverageMode by mutableStateOf(WeightMovingAverageMode.All)
    private var csvShareStatus by mutableStateOf<String?>(null)
    private var googleDriveStatus by mutableStateOf<String?>(null)
    private var isGoogleDriveAuthorizing by mutableStateOf(false)
    private var spreadsheetUploadStatus by mutableStateOf<String?>(null)
    private var isSpreadsheetUploading by mutableStateOf(false)
    private var pendingGoogleAuthorizationAction = GoogleAuthorizationAction.ConnectDrive
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedTextImportStatus by mutableStateOf<String?>(null)
    private var healthRefreshJob: Job? = null
    private var localRefreshJob: Job? = null
    private var automaticSpreadsheetSyncJob: Job? = null
    private var isAutomaticSpreadsheetSyncing = false
    private var automaticSpreadsheetSyncPending = false
    private var hasCompletedInitialHealthLoad = false
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
    private val startGoogleAuthorization = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val action = pendingGoogleAuthorizationAction
        try {
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            when (action) {
                GoogleAuthorizationAction.ConnectDrive -> {
                    googleDriveStatus = if (authorizationResult.accessToken.isNullOrBlank()) {
                        "Google Driveの認証トークンを取得できませんでした"
                    } else {
                        "Google Driveに接続しました"
                    }
                    isGoogleDriveAuthorizing = false
                }
                GoogleAuthorizationAction.UploadSpreadsheet -> {
                    continueSpreadsheetUpload(authorizationResult.accessToken)
                }
            }
        } catch (error: ApiException) {
            Log.e(TAG, "Google Drive authorization failed.", error)
            when (action) {
                GoogleAuthorizationAction.ConnectDrive -> {
                    googleDriveStatus = "Google Drive接続に失敗しました: ${googleAuthorizationFailureMessage(error)}"
                    isGoogleDriveAuthorizing = false
                }
                GoogleAuthorizationAction.UploadSpreadsheet -> {
                    spreadsheetUploadStatus = "スプレッドシート認証に失敗しました: ${googleAuthorizationFailureMessage(error)}"
                    isSpreadsheetUploading = false
                }
            }
        }
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
                        onGoogleDriveLogin = { authorizeGoogleDrive() },
                        googleDriveStatus = googleDriveStatus,
                        isGoogleDriveAuthorizing = isGoogleDriveAuthorizing,
                        onUploadSpreadsheet = { uploadSpreadsheetData() },
                        spreadsheetUploadStatus = spreadsheetUploadStatus,
                        isSpreadsheetUploading = isSpreadsheetUploading,
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
        if (result.imported) {
            refreshLocalHealthData()
            requestImmediateAutomaticSpreadsheetSync()
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
            val shouldSync = !hasCompletedInitialHealthLoad ||
                (loadedState.permissions is PermissionState.Granted &&
                    previousState.hasMirrorDataChanged(loadedState))
            hasCompletedInitialHealthLoad = true
            if (shouldSync) {
                scheduleAutomaticSpreadsheetSync()
            }
        }
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
                requestImmediateAutomaticSpreadsheetSync()
            }
        }
    }

    private fun invalidateManualRecord(id: String) {
        localStore.invalidateManualRecord(id)
        refreshLocalHealthData()
        requestImmediateAutomaticSpreadsheetSync()
    }

    private fun restoreManualRecord(id: String) {
        localStore.restoreManualRecord(id)
        refreshLocalHealthData()
        requestImmediateAutomaticSpreadsheetSync()
    }

    private fun deleteManualRecord(id: String) {
        localStore.deleteManualRecord(id)
        refreshLocalHealthData()
        requestImmediateAutomaticSpreadsheetSync()
    }

    private fun invalidateStoredRecord(recordType: String, uniqueKey: String) {
        localStore.invalidateStoredRecord(recordType, uniqueKey)
        refreshLocalHealthData()
        requestImmediateAutomaticSpreadsheetSync()
    }

    private fun restoreStoredRecord(recordType: String, uniqueKey: String) {
        localStore.restoreStoredRecord(recordType, uniqueKey)
        refreshLocalHealthData()
        requestImmediateAutomaticSpreadsheetSync()
    }

    private fun deleteStoredRecord(recordType: String, uniqueKey: String) {
        localStore.deleteStoredRecord(recordType, uniqueKey)
        refreshLocalHealthData()
        requestImmediateAutomaticSpreadsheetSync()
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
                requestImmediateAutomaticSpreadsheetSync()
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

    private fun authorizeGoogleDrive() {
        if (isGoogleDriveAuthorizing) {
            return
        }

        pendingGoogleAuthorizationAction = GoogleAuthorizationAction.ConnectDrive
        isGoogleDriveAuthorizing = true
        googleDriveStatus = "Google Driveの接続を確認中"
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (!authorizationResult.hasResolution()) {
                    if (authorizationResult.accessToken.isNullOrBlank()) {
                        googleDriveStatus = "Google Driveの認証トークンを取得できませんでした"
                    } else {
                        googleDriveStatus = "Google Driveに接続しました"
                    }
                    isGoogleDriveAuthorizing = false
                    return@addOnSuccessListener
                }

                val pendingIntent = authorizationResult.pendingIntent
                if (pendingIntent == null) {
                    googleDriveStatus = "Google Driveの認証画面を開けませんでした"
                    isGoogleDriveAuthorizing = false
                    return@addOnSuccessListener
                }
                googleDriveStatus = "Google Driveの認証を完了してください"
                startGoogleAuthorization.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                )
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to authorize Google Drive access.", error)
                googleDriveStatus = "Google Drive接続に失敗しました: ${googleAuthorizationFailureMessage(error)}"
                isGoogleDriveAuthorizing = false
            }
    }

    private fun uploadSpreadsheetData() {
        if (isSpreadsheetUploading || healthState.isLoading) {
            return
        }

        pendingGoogleAuthorizationAction = GoogleAuthorizationAction.UploadSpreadsheet
        isSpreadsheetUploading = true
        spreadsheetUploadStatus = "スプレッドシートの権限を確認中"
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (!authorizationResult.hasResolution()) {
                    continueSpreadsheetUpload(authorizationResult.accessToken)
                    return@addOnSuccessListener
                }

                val pendingIntent = authorizationResult.pendingIntent
                if (pendingIntent == null) {
                    spreadsheetUploadStatus = "スプレッドシートの認証画面を開けませんでした"
                    isSpreadsheetUploading = false
                    return@addOnSuccessListener
                }
                spreadsheetUploadStatus = "スプレッドシートの認証を完了してください"
                startGoogleAuthorization.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                )
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to authorize Google Sheets access.", error)
                spreadsheetUploadStatus = "スプレッドシート認証に失敗しました: ${googleAuthorizationFailureMessage(error)}"
                isSpreadsheetUploading = false
            }
    }

    private fun continueSpreadsheetUpload(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            spreadsheetUploadStatus = "スプレッドシートの認証トークンを取得できませんでした"
            isSpreadsheetUploading = false
            return
        }

        lifecycleScope.launch {
            spreadsheetUploadStatus = "スプレッドシートへ同期中"
            val currentData = withContext(Dispatchers.IO) {
                localStore.load()
            }
            val result = spreadsheetUploader.upload(
                data = currentData,
                accessToken = accessToken,
            )
            spreadsheetUploadStatus = when (result) {
                is SpreadsheetUploadResult.Success ->
                    "同期完了: ${result.synchronizedCount}件を携帯側の状態へ更新"
                is SpreadsheetUploadResult.Failure ->
                    "同期失敗: ${result.message}"
            }
            isSpreadsheetUploading = false
            if (result is SpreadsheetUploadResult.Success) {
                retryPendingAutomaticSpreadsheetSync()
            } else {
                automaticSpreadsheetSyncPending = false
            }
        }
    }

    private fun requestImmediateAutomaticSpreadsheetSync() {
        automaticSpreadsheetSyncJob?.cancel()
        automaticSpreadsheetSyncJob = null
        startAutomaticSpreadsheetSync()
    }

    private fun scheduleAutomaticSpreadsheetSync() {
        automaticSpreadsheetSyncJob?.cancel()
        automaticSpreadsheetSyncJob = lifecycleScope.launch {
            delay(AUTOMATIC_SYNC_DEBOUNCE_MS)
            startAutomaticSpreadsheetSync()
        }
    }

    private fun startAutomaticSpreadsheetSync() {
        if (isAutomaticSpreadsheetSyncing || isSpreadsheetUploading) {
            automaticSpreadsheetSyncPending = true
            return
        }

        automaticSpreadsheetSyncPending = false
        isAutomaticSpreadsheetSyncing = true
        spreadsheetUploadStatus = "携帯側を正として自動同期中"
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    isAutomaticSpreadsheetSyncing = false
                    automaticSpreadsheetSyncPending = false
                    spreadsheetUploadStatus = "自動同期待機: 設定画面で一度スプレッドシート同期を許可してください"
                    return@addOnSuccessListener
                }
                continueAutomaticSpreadsheetUpload(authorizationResult.accessToken)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Automatic Google Sheets synchronization failed.", error)
                isAutomaticSpreadsheetSyncing = false
                automaticSpreadsheetSyncPending = false
                spreadsheetUploadStatus = "自動同期失敗: ${googleAuthorizationFailureMessage(error)}"
            }
    }

    private fun continueAutomaticSpreadsheetUpload(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            isAutomaticSpreadsheetSyncing = false
            automaticSpreadsheetSyncPending = false
            spreadsheetUploadStatus = "自動同期待機: スプレッドシート権限が必要です"
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val currentData = localStore.load()
                spreadsheetUploader.upload(
                    data = currentData,
                    accessToken = accessToken,
                )
            }
            spreadsheetUploadStatus = when (result) {
                is SpreadsheetUploadResult.Success ->
                    "自動同期完了: ${result.synchronizedCount}件を反映"
                is SpreadsheetUploadResult.Failure ->
                    "自動同期失敗: ${result.message}"
            }
            isAutomaticSpreadsheetSyncing = false
            if (result is SpreadsheetUploadResult.Success) {
                retryPendingAutomaticSpreadsheetSync()
            } else {
                automaticSpreadsheetSyncPending = false
            }
        }
    }

    private fun retryPendingAutomaticSpreadsheetSync() {
        if (!automaticSpreadsheetSyncPending) return
        automaticSpreadsheetSyncPending = false
        startAutomaticSpreadsheetSync()
    }

    private fun googleAuthorizationFailureMessage(error: Exception): String {
        val message = error.localizedMessage ?: error.message
        if (message?.contains(API_CONSOLE_UNREGISTERED_STATUS, ignoreCase = true) == true) {
            return "OAuth Androidクライアント未登録です。packageName=$packageName、SHA-1=${currentSigningSha1() ?: "取得失敗"} を登録してください"
        }
        return message ?: "原因不明"
    }

    private fun currentSigningSha1(): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val signature = signatures?.firstOrNull()
            ?: return null
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(signature.toByteArray())
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }.getOrNull()

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

private enum class GoogleAuthorizationAction {
    ConnectDrive,
    UploadSpreadsheet,
}

private const val TAG = "HealthSheetSync"
private const val UNKNOWN = "不明"
private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
private const val API_CONSOLE_UNREGISTERED_STATUS = "UNREGISTERED_ON_API_CONSOLE"
private const val UNSYNCED_PAST_STEPS_MESSAGE = "未同期の過去日の歩数があります。先にHealth Connectを更新してください"
private const val AUTOMATIC_SYNC_DEBOUNCE_MS = 1_000L
