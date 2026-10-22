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
    val isLoaded: Boolean = false,
    val loadRevision: Long = 0,
)

internal interface LabReferenceSettingsGateway {
    fun initializeMissing(records: List<LabResult>): Map<String, LabReferenceRange?>
    fun units(): Map<String, String>
    fun save(itemName: String, range: LabReferenceRange?, unit: String): Map<String, LabReferenceRange?>
}

internal class LabReferenceSettingsCoordinator(
    private val store: LabReferenceSettingsGateway,
    private val scope: CoroutineScope,
    private val onStateChanged: (LabReferenceUiState) -> Unit,
) {
    constructor(context: Context, scope: CoroutineScope, onStateChanged: (LabReferenceUiState) -> Unit) : this(
        object : LabReferenceSettingsGateway {
            private val delegate = LabReferenceSettingsStore(context)
            override fun initializeMissing(records: List<LabResult>) = delegate.initializeMissing(records)
            override fun units() = delegate.units()
            override fun save(itemName: String, range: LabReferenceRange?, unit: String) =
                delegate.save(itemName, range, unit)
        },
        scope,
        onStateChanged,
    )

    private val mutex = Mutex()
    private var state = LabReferenceUiState()
    private var loadRevision = 0L

    fun initializeMissing(records: List<LabResult>) {
        val revision = ++loadRevision
        state = state.copy(error = null, isLoaded = false, loadRevision = revision)
        onStateChanged(state)
        scope.launch {
            mutex.withLock {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        LabReferenceUiState(
                            store.initializeMissing(records), store.units(),
                            isLoaded = true, loadRevision = revision,
                        )
                    }
                }
                if (revision == loadRevision) {
                    state = result.getOrElse { error ->
                        state.copy(error = error.message ?: "項目設定を読み込めませんでした", isLoaded = false)
                    }
                    onStateChanged(state)
                }
            }
        }
    }

    fun save(
        itemName: String,
        range: LabReferenceRange?,
        unit: String,
        onResult: (String?) -> Unit,
    ) {
        if (!state.isLoaded) {
            onResult("項目設定の読み込みが完了していません")
            return
        }
        scope.launch {
            mutex.withLock {
                if (!state.isLoaded) {
                    onResult("項目設定の読み込みが完了していません")
                    return@withLock
                }
                val revision = loadRevision
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        LabReferenceUiState(
                            store.save(itemName, range, unit), store.units(),
                            isLoaded = true, loadRevision = revision,
                        )
                    }
                }
                result.onSuccess {
                    state = if (revision == loadRevision) it else state.copy(settings = it.settings, units = it.units)
                    onStateChanged(state)
                    onResult(null)
                }.onFailure { error ->
                    onResult(error.message ?: "項目設定を保存できませんでした")
                }
            }
        }
    }
}
