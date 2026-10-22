package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface ManualRecordSaveResult {
    data object Saved : ManualRecordSaveResult
    data class SavedWithWarning(val message: String) : ManualRecordSaveResult
    data class Failed(val message: String) : ManualRecordSaveResult
}

internal class ManualRecordSaveUseCase(
    private val saveRecord: (ManualHealthRecordDraft) -> Unit,
    private val finalizeSteps: (LocalDate, Long, Int) -> Boolean,
) {
    constructor(localStore: LocalHealthDataStore) : this(
        localStore::saveManualRecord,
        localStore::finalizeManualStepsForDate,
    )

    suspend fun save(draft: ManualHealthRecordDraft, basalMetabolicRate: Int): ManualRecordSaveResult =
        withContext(Dispatchers.IO) {
            if (runCatching { saveRecord(draft) }.isFailure) {
                return@withContext ManualRecordSaveResult.Failed("記録を保存できませんでした")
            }

            if (draft.type != ManualRecordType.Steps) return@withContext ManualRecordSaveResult.Saved
            val steps = draft.valueText.removeSuffix("歩").toLongOrNull()
                ?: return@withContext ManualRecordSaveResult.SavedWithWarning(
                    "歩数は保存しましたが、消費量の確定に必要な値を読み取れませんでした",
                )
            if (runCatching {
                    finalizeSteps(draft.measuredAt.toLocalDate(), steps, basalMetabolicRate)
                }.isFailure
            ) {
                ManualRecordSaveResult.SavedWithWarning("歩数は保存しましたが、消費量を確定できませんでした")
            } else {
                ManualRecordSaveResult.Saved
            }
        }
}
