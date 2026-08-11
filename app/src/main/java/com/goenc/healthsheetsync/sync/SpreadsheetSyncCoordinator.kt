package com.goenc.healthsheetsync.sync

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import com.goenc.healthsheetsync.data.SpreadsheetUploadResult
import com.goenc.healthsheetsync.data.SpreadsheetUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SpreadsheetSyncUiState(
    val googleDriveStatus: String? = null,
    val isGoogleDriveAuthorizing: Boolean = false,
    val spreadsheetUploadStatus: String? = null,
    val isSpreadsheetUploading: Boolean = false,
)

internal class SpreadsheetSyncCoordinator(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val localStore: LocalHealthDataStore,
    private val spreadsheetUploader: SpreadsheetUploader = SpreadsheetUploader(),
    private val onStateChanged: (SpreadsheetSyncUiState) -> Unit,
) {
    private var state = SpreadsheetSyncUiState()
    private var pendingGoogleAuthorizationAction = GoogleAuthorizationAction.ConnectDrive
    private var automaticSpreadsheetSyncJob: Job? = null
    private var isAutomaticSpreadsheetSyncing = false
    private var automaticSpreadsheetSyncPending = false

    private val startGoogleAuthorization = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val action = pendingGoogleAuthorizationAction
        try {
            val authorizationResult = Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(result.data)
            when (action) {
                GoogleAuthorizationAction.ConnectDrive -> {
                    updateState {
                        copy(
                            googleDriveStatus = if (authorizationResult.accessToken.isNullOrBlank()) {
                                "Google Driveの認証トークンを取得できませんでした"
                            } else {
                                "Google Driveに接続しました"
                            },
                            isGoogleDriveAuthorizing = false,
                        )
                    }
                }
                GoogleAuthorizationAction.UploadSpreadsheet -> {
                    continueSpreadsheetUpload(authorizationResult.accessToken)
                }
            }
        } catch (error: ApiException) {
            Log.e(TAG, "Google Drive authorization failed.", error)
            when (action) {
                GoogleAuthorizationAction.ConnectDrive -> {
                    updateState {
                        copy(
                            googleDriveStatus =
                                "Google Drive接続に失敗しました: ${googleAuthorizationFailureMessage(error)}",
                            isGoogleDriveAuthorizing = false,
                        )
                    }
                }
                GoogleAuthorizationAction.UploadSpreadsheet -> {
                    updateState {
                        copy(
                            spreadsheetUploadStatus =
                                "スプレッドシート認証に失敗しました: ${googleAuthorizationFailureMessage(error)}",
                            isSpreadsheetUploading = false,
                        )
                    }
                }
            }
        }
    }

    fun authorizeGoogleDrive() {
        if (state.isGoogleDriveAuthorizing) return

        pendingGoogleAuthorizationAction = GoogleAuthorizationAction.ConnectDrive
        updateState {
            copy(
                googleDriveStatus = "Google Driveの接続を確認中",
                isGoogleDriveAuthorizing = true,
            )
        }
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (!authorizationResult.hasResolution()) {
                    updateState {
                        copy(
                            googleDriveStatus = if (authorizationResult.accessToken.isNullOrBlank()) {
                                "Google Driveの認証トークンを取得できませんでした"
                            } else {
                                "Google Driveに接続しました"
                            },
                            isGoogleDriveAuthorizing = false,
                        )
                    }
                    return@addOnSuccessListener
                }

                val pendingIntent = authorizationResult.pendingIntent
                if (pendingIntent == null) {
                    updateState {
                        copy(
                            googleDriveStatus = "Google Driveの認証画面を開けませんでした",
                            isGoogleDriveAuthorizing = false,
                        )
                    }
                    return@addOnSuccessListener
                }
                updateState { copy(googleDriveStatus = "Google Driveの認証を完了してください") }
                startGoogleAuthorization.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                )
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to authorize Google Drive access.", error)
                updateState {
                    copy(
                        googleDriveStatus =
                            "Google Drive接続に失敗しました: ${googleAuthorizationFailureMessage(error)}",
                        isGoogleDriveAuthorizing = false,
                    )
                }
            }
    }

    fun uploadSpreadsheetData(isHealthLoading: Boolean) {
        if (state.isSpreadsheetUploading || isHealthLoading) return

        pendingGoogleAuthorizationAction = GoogleAuthorizationAction.UploadSpreadsheet
        updateState {
            copy(
                spreadsheetUploadStatus = "スプレッドシートの権限を確認中",
                isSpreadsheetUploading = true,
            )
        }
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (!authorizationResult.hasResolution()) {
                    continueSpreadsheetUpload(authorizationResult.accessToken)
                    return@addOnSuccessListener
                }

                val pendingIntent = authorizationResult.pendingIntent
                if (pendingIntent == null) {
                    updateState {
                        copy(
                            spreadsheetUploadStatus = "スプレッドシートの認証画面を開けませんでした",
                            isSpreadsheetUploading = false,
                        )
                    }
                    return@addOnSuccessListener
                }
                updateState { copy(spreadsheetUploadStatus = "スプレッドシートの認証を完了してください") }
                startGoogleAuthorization.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                )
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to authorize Google Sheets access.", error)
                updateState {
                    copy(
                        spreadsheetUploadStatus =
                            "スプレッドシート認証に失敗しました: ${googleAuthorizationFailureMessage(error)}",
                        isSpreadsheetUploading = false,
                    )
                }
            }
    }

    fun requestImmediateAutomaticSync() {
        automaticSpreadsheetSyncJob?.cancel()
        automaticSpreadsheetSyncJob = null
        startAutomaticSpreadsheetSync()
    }

    fun scheduleAutomaticSync() {
        automaticSpreadsheetSyncJob?.cancel()
        automaticSpreadsheetSyncJob = scope.launch {
            delay(AUTOMATIC_SYNC_DEBOUNCE_MS)
            startAutomaticSpreadsheetSync()
        }
    }

    private fun continueSpreadsheetUpload(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            updateState {
                copy(
                    spreadsheetUploadStatus = "スプレッドシートの認証トークンを取得できませんでした",
                    isSpreadsheetUploading = false,
                )
            }
            return
        }

        scope.launch {
            updateState { copy(spreadsheetUploadStatus = "スプレッドシートへ同期中") }
            val currentData = withContext(Dispatchers.IO) {
                localStore.load()
            }
            val result = spreadsheetUploader.upload(
                data = currentData,
                accessToken = accessToken,
            )
            updateState {
                copy(
                    spreadsheetUploadStatus = when (result) {
                        is SpreadsheetUploadResult.Success ->
                            "同期完了: ${result.synchronizedCount}件を携帯側の状態へ更新"
                        is SpreadsheetUploadResult.Failure ->
                            "同期失敗: ${result.message}"
                    },
                    isSpreadsheetUploading = false,
                )
            }
            if (result is SpreadsheetUploadResult.Success) {
                retryPendingAutomaticSpreadsheetSync()
            } else {
                automaticSpreadsheetSyncPending = false
            }
        }
    }

    private fun startAutomaticSpreadsheetSync() {
        if (isAutomaticSpreadsheetSyncing || state.isSpreadsheetUploading) {
            automaticSpreadsheetSyncPending = true
            return
        }

        automaticSpreadsheetSyncPending = false
        isAutomaticSpreadsheetSyncing = true
        updateState { copy(spreadsheetUploadStatus = "携帯側を正として自動同期中") }
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    isAutomaticSpreadsheetSyncing = false
                    automaticSpreadsheetSyncPending = false
                    updateState {
                        copy(spreadsheetUploadStatus = "自動同期待機: 設定画面で一度スプレッドシート同期を許可してください")
                    }
                    return@addOnSuccessListener
                }
                continueAutomaticSpreadsheetUpload(authorizationResult.accessToken)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Automatic Google Sheets synchronization failed.", error)
                isAutomaticSpreadsheetSyncing = false
                automaticSpreadsheetSyncPending = false
                updateState {
                    copy(spreadsheetUploadStatus = "自動同期失敗: ${googleAuthorizationFailureMessage(error)}")
                }
            }
    }

    private fun continueAutomaticSpreadsheetUpload(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            isAutomaticSpreadsheetSyncing = false
            automaticSpreadsheetSyncPending = false
            updateState { copy(spreadsheetUploadStatus = "自動同期待機: スプレッドシート権限が必要です") }
            return
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val currentData = localStore.load()
                spreadsheetUploader.upload(
                    data = currentData,
                    accessToken = accessToken,
                )
            }
            updateState {
                copy(
                    spreadsheetUploadStatus = when (result) {
                        is SpreadsheetUploadResult.Success ->
                            "自動同期完了: ${result.synchronizedCount}件を反映"
                        is SpreadsheetUploadResult.Failure ->
                            "自動同期失敗: ${result.message}"
                    },
                )
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

    private fun updateState(transform: SpreadsheetSyncUiState.() -> SpreadsheetSyncUiState) {
        state = state.transform()
        onStateChanged(state)
    }

    private fun googleAuthorizationFailureMessage(error: Exception): String {
        val message = error.localizedMessage ?: error.message
        if (message?.contains(API_CONSOLE_UNREGISTERED_STATUS, ignoreCase = true) == true) {
            return "OAuth Androidクライアント未登録です。packageName=${activity.packageName}、" +
                "SHA-1=${currentSigningSha1() ?: "取得失敗"} を登録してください"
        }
        return message ?: "原因不明"
    }

    private fun currentSigningSha1(): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.GET_SIGNATURES,
            ).signatures
        }
        val signature = signatures?.firstOrNull() ?: return null
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(signature.toByteArray())
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }.getOrNull()

    private enum class GoogleAuthorizationAction {
        ConnectDrive,
        UploadSpreadsheet,
    }

    private companion object {
        private const val TAG = "HealthSheetSync"
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
        private const val API_CONSOLE_UNREGISTERED_STATUS = "UNREGISTERED_ON_API_CONSOLE"
        private const val AUTOMATIC_SYNC_DEBOUNCE_MS = 1_000L
    }
}
