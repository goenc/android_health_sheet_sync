package com.goenc.healthsheetsync.sync

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.goenc.healthsheetsync.data.PfcBoardReader
import com.goenc.healthsheetsync.data.PfcBoardState
import com.goenc.healthsheetsync.data.PfcBoardReadException
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import com.goenc.healthsheetsync.data.LabResultsReader
import com.goenc.healthsheetsync.data.LabResultsUiState
import com.goenc.healthsheetsync.data.LabResult
import com.goenc.healthsheetsync.data.LabResultsWriter
import com.goenc.healthsheetsync.data.LabResultsSaveResult
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
    val labResults: LabResultsUiState = LabResultsUiState(),
    val pfcBoard: PfcBoardState = PfcBoardState(),
)

internal class SpreadsheetSyncCoordinator(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val localStore: LocalHealthDataStore,
    private val spreadsheetUploader: SpreadsheetUploader = SpreadsheetUploader(),
    private val onStateChanged: (SpreadsheetSyncUiState) -> Unit,
) {
    private val pfcBoardReader = PfcBoardReader(activity.applicationContext)
    private var pfcTokenRefreshAttempted = false
    private val labResultsReader = LabResultsReader(activity.applicationContext)
    private val labResultsWriter = LabResultsWriter(labResultsReader)
    private var pendingLabSaveResult: LabResultsSaveResult? = null
    private var labImportResult: ((String?) -> Unit)? = null
    private var state = SpreadsheetSyncUiState(labResults = labResultsReader.loadCached())
    private var pendingGoogleAuthorizationAction = GoogleAuthorizationAction.ConnectDrive
    private var automaticSpreadsheetSyncJob: Job? = null
    private var isAutomaticSpreadsheetSyncing = false
    private var automaticSpreadsheetSyncPending = false

    init {
        onStateChanged(state)
    }

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
                GoogleAuthorizationAction.LoadLabResults -> continueLabResultsRead(authorizationResult.accessToken)
                GoogleAuthorizationAction.ImportLabResults -> continueLabResultsImport(authorizationResult.accessToken)
                GoogleAuthorizationAction.LoadPfcBoard -> continuePfcBoardRead(authorizationResult.accessToken)
            }
        } catch (error: ApiException) {
            if (action != GoogleAuthorizationAction.LoadPfcBoard) Log.e(TAG, "Google Drive authorization failed.", error)
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
                GoogleAuthorizationAction.LoadLabResults -> labResultsFailure("Google認証を完了できませんでした")
                GoogleAuthorizationAction.ImportLabResults -> labResultsFailure("Google認証を完了できませんでした")
                GoogleAuthorizationAction.LoadPfcBoard -> pfcBoardFailure("Google認証を完了できませんでした（code=${error.statusCode}）")
            }
        }
    }

    fun authorizeGoogleDrive() {
        if (state.isGoogleDriveAuthorizing || state.isSpreadsheetUploading || state.labResults.isLoading || state.pfcBoard.loading) return

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
        if (state.isSpreadsheetUploading || state.isGoogleDriveAuthorizing || state.labResults.isLoading || state.pfcBoard.loading || isHealthLoading) return

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

    fun loadLocalPfcBoard() {
        val initial = state.pfcBoard
        scope.launch {
            val loaded = pfcBoardReader.cached()
            if (state.pfcBoard === initial) updateState { copy(pfcBoard = loaded) }
        }
    }

    fun refreshPfcBoard() {
        if (state.pfcBoard.loading) return
        if (state.isGoogleDriveAuthorizing || state.isSpreadsheetUploading || state.labResults.isLoading || isAutomaticSpreadsheetSyncing) {
            pfcBoardFailure("Google認証・同期が完了してから更新してください")
            return
        }
        pendingGoogleAuthorizationAction = GoogleAuthorizationAction.LoadPfcBoard
        pfcTokenRefreshAttempted = false
        updateState { copy(pfcBoard = pfcBoard.copy(loading = true, status = "比較ボードを取得中")) }
        authorizePfcBoard()
    }

    private fun authorizePfcBoard() {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE))).build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                if (!result.hasResolution()) continuePfcBoardRead(result.accessToken)
                else {
                    val pending = result.pendingIntent
                    if (pending == null) pfcBoardFailure("Google認証画面を開けませんでした")
                    else startGoogleAuthorization.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                }
            }
            .addOnFailureListener { error ->
                val code = (error as? ApiException)?.statusCode
                pfcBoardFailure("Google認証に失敗（code=${code ?: "不明"}）。OAuth登録・同意設定を確認してください")
            }
    }

    private fun continuePfcBoardRead(token: String?) {
        if (token.isNullOrBlank()) {
            pfcBoardFailure("Google認証トークンを取得できませんでした")
            return
        }
        updateState { copy(pfcBoard = pfcBoard.copy(status = "比較ボードの一覧を取得中")) }
        scope.launch {
            try {
                val loaded = pfcBoardReader.read(token) { completed, total ->
                    withContext(Dispatchers.Main) {
                        updateState { copy(pfcBoard = pfcBoard.copy(status = "比較ボードを復元中 $completed/$total")) }
                    }
                }
                updateState { copy(pfcBoard = loaded) }
                retryPendingAutomaticSpreadsheetSync()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (error is PfcBoardReadException && error.retryAuthorization && !pfcTokenRefreshAttempted) {
                    // OAuth登録の移設後も旧tokenがキャッシュされる。保存データや同意は消さず一度だけ再取得。
                    pfcTokenRefreshAttempted = true
                    updateState { copy(pfcBoard = pfcBoard.copy(status = "Google認証トークンを再取得中")) }
                    Identity.getAuthorizationClient(activity)
                        .clearToken(ClearTokenRequest.builder().setToken(token).build())
                        .addOnSuccessListener { authorizePfcBoard() }
                        .addOnFailureListener { pfcBoardFailure("認証キャッシュの更新に失敗。再度更新してください") }
                    return@launch
                }
                // HTTP本文・例外・同期payloadをログや画面へ流さない。
                pfcBoardFailure((error as? PfcBoardReadException)?.message
                    ?: "取得・復元に失敗。通信とPCの同期形式を確認してください")
            }
        }
    }

    private fun pfcBoardFailure(message: String) {
        updateState { copy(pfcBoard = pfcBoard.copy(loading = false, status = "取得失敗: $message")) }
        retryPendingAutomaticSpreadsheetSync()
    }

    fun loadLocalLabResults() {
        if (state.labResults.isLoading) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { labResultsReader.loadCached() }
            if (!state.labResults.isLoading) updateState { copy(labResults = loaded) }
        }
    }

    fun refreshLabResults() {
        if (state.labResults.isLoading) return
        if (state.isGoogleDriveAuthorizing || state.isSpreadsheetUploading || state.pfcBoard.loading) {
            labResultsFailure("Google認証・同期が完了してから更新してください")
            return
        }
        pendingGoogleAuthorizationAction = GoogleAuthorizationAction.LoadLabResults
        updateState { copy(labResults = labResults.copy(isLoading = true, status = "検査結果を取得中")) }
        authorizeLabResults(true)
    }

    fun importLabResults(records: List<LabResult>, onResult: (String?) -> Unit) {
        if (state.labResults.isLoading || state.pfcBoard.loading || state.isGoogleDriveAuthorizing || state.isSpreadsheetUploading) {
            onResult("取得・認証・同期が完了してから保存してください")
            return
        }
        if (records.isEmpty()) {
            onResult("取り込む検査結果がありません")
            return
        }
        labImportResult = onResult
        updateState { copy(labResults = labResults.copy(isLoading = true, status = "端末内DBへ保存中")) }
        scope.launch {
            try {
                pendingLabSaveResult = labResultsWriter.saveLocal(records)
                val loaded = withContext(Dispatchers.IO) { labResultsReader.loadCached() }
                updateState { copy(labResults = loaded.copy(isLoading = true, status = "DB保存済み。Sheetsへ反映中")) }
                pendingGoogleAuthorizationAction = GoogleAuthorizationAction.ImportLabResults
                authorizeLabResults(true)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val message = error.message ?: "端末内DBへの保存に失敗しました"
                updateState { copy(labResults = labResults.copy(isLoading = false, status = message)) }
                finishLabImport(message)
            }
        }
    }

    /** 項目設定のDB保存が終わった直後に呼び出す。通常表示ではSheetsを読まない。 */
    fun backupLabSettings() {
        scope.launch {
            while (state.labResults.isLoading || state.pfcBoard.loading || state.isGoogleDriveAuthorizing || state.isSpreadsheetUploading) delay(250)
            pendingGoogleAuthorizationAction = GoogleAuthorizationAction.ImportLabResults
            updateState { copy(labResults = labResults.copy(isLoading = true, status = "項目設定をSheetsへ反映中")) }
            authorizeLabResults(true)
        }
    }

    private fun authorizeLabResults(allowAuthorizationResolution: Boolean) {
        // 取得・利用者が確定した追記とも、既存同期と同じSheetsスコープを使う。
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SHEETS_SCOPE))).build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                if (!result.hasResolution()) {
                    if (pendingGoogleAuthorizationAction == GoogleAuthorizationAction.ImportLabResults) {
                        continueLabResultsImport(result.accessToken)
                    } else continueLabResultsRead(result.accessToken)
                } else if (!allowAuthorizationResolution) {
                    labResultsFailure("Google認証が必要です。「更新」を押してください")
                } else {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        labResultsFailure("Google認証画面を開けませんでした")
                    } else {
                        startGoogleAuthorization.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    }
                }
            }
            .addOnFailureListener { labResultsFailure("Google認証に失敗しました。再度更新してください") }
    }

    private fun continueLabResultsRead(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            labResultsFailure("Google認証トークンを取得できませんでした。再度更新してください")
            return
        }
        scope.launch {
            try {
                // 未反映のローカル保存を先に保護。再取得は明示的な「更新」だけ。
                while (labResultsWriter.backupIfNeeded(accessToken)) { /* 保存中の次世代変更も反映 */ }
                val loaded = labResultsReader.read(accessToken)
                updateState { copy(labResults = loaded) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                labResultsFailure(error.message ?: "検査結果の取得に失敗しました")
            }
        }
    }

    private fun labResultsFailure(message: String) {
        val localSaved = pendingGoogleAuthorizationAction == GoogleAuthorizationAction.ImportLabResults
        updateState { copy(labResults = labResults.copy(isLoading = false,
            status = if (localSaved) "DB保存済み。Sheets未反映: $message。「更新」で再試行できる" else message)) }
        finishLabImport(if (localSaved) null else message)
    }

    private fun finishLabImport(error: String?) {
        val callback = labImportResult
        labImportResult = null
        pendingLabSaveResult = null
        callback?.invoke(error)
    }

    private fun continueLabResultsImport(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            labResultsFailure("Google認証トークンを取得できませんでした")
            return
        }
        scope.launch {
            try {
                while (labResultsWriter.backupIfNeeded(accessToken)) { /* 未反映世代が残る間だけ送信 */ }
                val loaded = withContext(Dispatchers.IO) { labResultsReader.loadCached() }
                val saved = pendingLabSaveResult
                updateState { copy(labResults = loaded.copy(status = if (saved == null) "項目設定をSheetsへ反映済み"
                    else "${saved.addedCount}件をDB保存・Sheets反映、同一結果${saved.duplicateCount}件を除外")) }
                finishLabImport(null)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                labResultsFailure(error.message ?: "検査結果の保存に失敗しました")
            }
        }
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
        if (isAutomaticSpreadsheetSyncing || state.isSpreadsheetUploading || state.pfcBoard.loading) {
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
        LoadLabResults,
        ImportLabResults,
        LoadPfcBoard,
    }

    private companion object {
        private const val TAG = "HealthSheetSync"
        private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
        private const val API_CONSOLE_UNREGISTERED_STATUS = "UNREGISTERED_ON_API_CONSOLE"
        private const val AUTOMATIC_SYNC_DEBOUNCE_MS = 1_000L
    }
}
