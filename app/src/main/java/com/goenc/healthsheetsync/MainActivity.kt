package com.goenc.healthsheetsync

import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import com.goenc.healthsheetsync.data.OneTouchRevealTextParser
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.goenc.healthsheetsync.data.SpreadsheetUploadSettings
import com.goenc.healthsheetsync.data.SpreadsheetUploadResult
import com.goenc.healthsheetsync.data.SpreadsheetUploader
import com.goenc.healthsheetsync.health.HealthConnectDebugReader
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.ui.HealthDebugScreen
import com.goenc.healthsheetsync.ui.theme.HealthSheetSyncTheme
import kotlinx.coroutines.launch
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    private lateinit var healthReader: HealthConnectDebugReader
    private lateinit var localStore: LocalHealthDataStore
    private val spreadsheetUploader = SpreadsheetUploader()
    private var healthState by mutableStateOf(HealthDebugUiState())
    private var externalSaveStatus by mutableStateOf<String?>(null)
    private var spreadsheetUploadStatus by mutableStateOf<String?>(null)
    private var isSpreadsheetUploading by mutableStateOf(false)
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
    private val startGoogleAuthorization = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        try {
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            continueSpreadsheetUpload(authorizationResult.accessToken)
        } catch (error: ApiException) {
            Log.e(TAG, "Google authorization failed.", error)
            isSpreadsheetUploading = false
            spreadsheetUploadStatus = "Googleログインが完了しませんでした: ${googleAuthorizationFailureMessage(error)}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthReader = HealthConnectDebugReader(applicationContext)
        localStore = LocalHealthDataStore(applicationContext)
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
                        onSaveExternalWorkbook = {
                            externalSaveStatus = null
                            createExternalWorkbook.launch(DEFAULT_WORKBOOK_NAME)
                        },
                        externalSaveStatus = externalSaveStatus,
                        onUploadSpreadsheet = { uploadSpreadsheetData() },
                        spreadsheetUploadStatus = spreadsheetUploadStatus,
                        isSpreadsheetUploading = isSpreadsheetUploading,
                        targetSpreadsheetUrl = SpreadsheetUploadSettings.TARGET_SPREADSHEET_URL,
                        sharedText = sharedText,
                        sharedTextImportStatus = sharedTextImportStatus,
                        onSaveManualRecord = { draft -> saveManualRecord(draft) },
                        onInvalidateStoredRecord = { recordType, uniqueKey ->
                            invalidateStoredRecord(recordType, uniqueKey)
                        },
                        onRestoreStoredRecord = { recordType, uniqueKey ->
                            restoreStoredRecord(recordType, uniqueKey)
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
        if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)) {
            return
        }
        val text = readSharedText(intent)
        if (text.isBlank()) return
        sharedText = text
        val glucoseRecords = OneTouchRevealTextParser.parse(text)
        if (glucoseRecords.isEmpty()) {
            sharedTextImportStatus = "共有テキストから血糖値を読み取れませんでした"
            return
        }
        localStore.save(
            weightRecords = emptyList(),
            glucoseRecords = glucoseRecords,
            stepDailyRecords = emptyList(),
        )
        sharedTextImportStatus = "共有テキストから血糖${glucoseRecords.size}件を取り込みました"
        refreshHealthData()
    }

    private fun readSharedText(intent: Intent): String {
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val streamTexts = buildList {
            intent.data?.let { uri ->
                readTextFromUri(uri)?.let(::add)
            }
            intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                readTextFromUri(uri)?.let(::add)
            }
            intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)
                ?.mapNotNull(::readTextFromUri)
                ?.let(::addAll)
            intent.clipData?.let { clipData ->
                for (index in 0 until clipData.itemCount) {
                    clipData.getItemAt(index).uri?.let { uri ->
                        readTextFromUri(uri)?.let(::add)
                    }
                }
            }
        }
        return (listOf(extraText) + streamTexts)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun readTextFromUri(uri: Uri): String? {
        return runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read shared file: $uri", error)
            null
        }
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

    private fun invalidateStoredRecord(recordType: String, uniqueKey: String) {
        localStore.invalidateStoredRecord(recordType, uniqueKey)
        refreshHealthData()
    }

    private fun restoreStoredRecord(recordType: String, uniqueKey: String) {
        localStore.restoreStoredRecord(recordType, uniqueKey)
        refreshHealthData()
    }

    private fun uploadSpreadsheetData() {
        if (isSpreadsheetUploading) {
            return
        }

        isSpreadsheetUploading = true
        spreadsheetUploadStatus = "Googleログインを確認中"
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    val pendingIntent = authorizationResult.pendingIntent
                    if (pendingIntent == null) {
                        isSpreadsheetUploading = false
                        spreadsheetUploadStatus = "Googleログイン画面を開けませんでした"
                        return@addOnSuccessListener
                    }
                    spreadsheetUploadStatus = "Googleログインを完了してください"
                    startGoogleAuthorization.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                    )
                } else {
                    continueSpreadsheetUpload(authorizationResult.accessToken)
                }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to authorize Google Sheets access.", error)
                isSpreadsheetUploading = false
                spreadsheetUploadStatus = "Googleログインに失敗しました: ${googleAuthorizationFailureMessage(error)}"
            }
    }

    private fun continueSpreadsheetUpload(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            isSpreadsheetUploading = false
            spreadsheetUploadStatus = "Google認証トークンを取得できませんでした"
            return
        }

        lifecycleScope.launch {
            spreadsheetUploadStatus = "アップロード中"
            spreadsheetUploadStatus = when (
                val result = spreadsheetUploader.upload(
                    state = healthState,
                    accessToken = accessToken,
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

    private fun googleAuthorizationFailureMessage(error: Exception): String {
        val message = error.localizedMessage ?: error.message
        if (message?.contains(API_CONSOLE_UNREGISTERED_STATUS, ignoreCase = true) == true) {
            val registrationValues = buildList {
                add("packageName=$packageName")
                currentSigningSha1()?.let { sha1 -> add("SHA-1=$sha1") }
            }.joinToString("、")
            return "Google API Console の OAuth Android クライアント未登録です。$registrationValues を登録してください"
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
        val signature = signatures?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
        digest.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }.getOrNull()

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name) as? T
        }
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(
        name: String,
    ): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(name)
        }
    }
}

private const val TAG = "HealthSheetSync"
private const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
private const val API_CONSOLE_UNREGISTERED_STATUS = "UNREGISTERED_ON_API_CONSOLE"
private const val WORKBOOK_ASSET_NAME = "health_sheet_sync_work_branch_template.xlsx"
private const val DEFAULT_WORKBOOK_NAME = "health_sheet_sync.xlsx"
private const val WORKBOOK_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
