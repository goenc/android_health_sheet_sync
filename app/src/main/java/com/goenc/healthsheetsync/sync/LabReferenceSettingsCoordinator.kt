package com.goenc.healthsheetsync.sync

import android.content.Context
import com.goenc.healthsheetsync.data.LabReferenceRange
import com.goenc.healthsheetsync.data.LabReferenceSettingsStore
import com.goenc.healthsheetsync.data.LabResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class LabReferenceUiState(
    val settings: Map<String, LabReferenceRange?> = emptyMap(),
    val units: Map<String, String> = emptyMap(),
    val error: String? = null,
)

internal class LabReferenceSettingsCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: (LabReferenceUiState) -> Unit,
) {
    private val store = LabReferenceSettingsStore(context)
    private val mutex = Mutex()
    private var state = LabReferenceUiState()

    fun initializeMissing(records: List<LabResult>) {
        scope.launch {
            mutex.withLock {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        LabReferenceUiState(store.initializeMissing(records), store.units())
                    }
                }
                state = result.getOrElse { error ->
                    state.copy(error = error.message ?: "項目設定を読み込めませんでした")
                }
                onStateChanged(state)
            }
        }
    }

    fun save(
        itemName: String,
        range: LabReferenceRange?,
        unit: String,
        onResult: (String?) -> Unit,
    ) {
        scope.launch {
            mutex.withLock {
                val result = withContext(Dispatchers.IO) {
                    runCatching { LabReferenceUiState(store.save(itemName, range, unit), store.units()) }
                }
                result.onSuccess {
                    state = it
                    onStateChanged(state)
                    onResult(null)
                }.onFailure { error ->
                    onResult(error.message ?: "項目設定を保存できませんでした")
                }
            }
        }
    }
}
