package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecordSaveUseCaseTest {
    private val date = LocalDate.of(2026, 10, 22)

    @Test
    fun failedSaveDoesNotReportCompletionOrFinalizeSteps() = runBlocking {
        var finalized = false
        val useCase = ManualRecordSaveUseCase(
            saveRecord = { error("保存失敗") },
            finalizeSteps = { _, _, _ -> finalized = true; true },
        )

        val result = useCase.save(stepsDraft(), 1500)

        assertTrue(result is ManualRecordSaveResult.Failed)
        assertFalse(finalized)
    }

    @Test
    fun failedFinalizationReportsThatStepsWereSaved() = runBlocking {
        var saved = false
        val useCase = ManualRecordSaveUseCase(
            saveRecord = { saved = true },
            finalizeSteps = { _, _, _ -> error("確定失敗") },
        )

        val result = useCase.save(stepsDraft(), 1500)

        assertTrue(saved)
        assertTrue(result is ManualRecordSaveResult.SavedWithWarning)
    }

    @Test
    fun successfulStepsSaveFinalizesTheRequestedDate() = runBlocking {
        var finalizedDate: LocalDate? = null
        val useCase = ManualRecordSaveUseCase(
            saveRecord = {},
            finalizeSteps = { targetDate, steps, basalMetabolicRate ->
                finalizedDate = targetDate
                assertEquals(8000L, steps)
                assertEquals(1500, basalMetabolicRate)
                true
            },
        )

        val result = useCase.save(stepsDraft(), 1500)

        assertEquals(ManualRecordSaveResult.Saved, result)
        assertEquals(date, finalizedDate)
    }

    private fun stepsDraft() = ManualHealthRecordDraft(
        type = ManualRecordType.Steps,
        measuredAt = date.atStartOfDay(),
        valueText = "8000歩",
    )
}
