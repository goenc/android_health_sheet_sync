package com.goenc.healthsheetsync.sync

import com.goenc.healthsheetsync.data.LabReferenceRange
import com.goenc.healthsheetsync.data.LabResult
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabReferenceSettingsCoordinatorTest {
    @Test
    fun saveIsRejectedUntilDelayedSettingsLoadCompletes() = runBlocking {
        val loadEntered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val range = LabReferenceRange(BigDecimal("1"), BigDecimal("2"))
        var saveCount = 0
        val store = FakeGateway(
            load = {
                loadEntered.countDown()
                check(releaseLoad.await(5, TimeUnit.SECONDS))
                mapOf("検査項目" to range)
            },
            unitsAction = { mapOf("検査項目" to "mg/dL") },
            saveAction = { _, _, _ ->
                saveCount++
                mapOf("検査項目" to range)
            },
        )
        val states = Channel<LabReferenceUiState>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LabReferenceSettingsCoordinator(store, scope) { states.trySend(it) }
            coordinator.initializeMissing(emptyList())
            assertFalse(withTimeout(5000) { states.receive() }.isLoaded)
            assertTrue(loadEntered.await(5, TimeUnit.SECONDS))

            var error: String? = null
            coordinator.save("検査項目", null, "") { error = it }
            assertEquals("項目設定の読み込みが完了していません", error)
            assertEquals(0, saveCount)

            releaseLoad.countDown()
            val loaded = withTimeout(5000) { states.receive() }
            assertTrue(loaded.isLoaded)
            assertEquals(range, loaded.settings["検査項目"])
            assertEquals("mg/dL", loaded.units["検査項目"])
        } finally {
            releaseLoad.countDown()
            scope.cancel()
        }
    }

    @Test
    fun failedLoadKeepsSavingDisabled() = runBlocking {
        var saveCount = 0
        val store = FakeGateway(
            load = { error("読み込み失敗") },
            saveAction = { _, _, _ -> saveCount++; emptyMap() },
        )
        val states = Channel<LabReferenceUiState>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LabReferenceSettingsCoordinator(store, scope) { states.trySend(it) }
            coordinator.initializeMissing(emptyList())
            assertFalse(withTimeout(5000) { states.receive() }.isLoaded)
            val failed = withTimeout(5000) { states.receive() }
            assertFalse(failed.isLoaded)
            assertEquals("読み込み失敗", failed.error)

            var error: String? = null
            coordinator.save("検査項目", null, "") { error = it }
            assertEquals("項目設定の読み込みが完了していません", error)
            assertEquals(0, saveCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun successfullyLoadedEmptySettingsAllowNewItemSave() = runBlocking {
        val store = FakeGateway(load = { emptyMap() })
        val states = Channel<LabReferenceUiState>(Channel.UNLIMITED)
        val results = Channel<String?>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LabReferenceSettingsCoordinator(store, scope) { states.trySend(it) }
            coordinator.initializeMissing(emptyList())
            assertFalse(withTimeout(5000) { states.receive() }.isLoaded)
            assertTrue(withTimeout(5000) { states.receive() }.isLoaded)
            coordinator.save("検査項目", LabReferenceRange(minimum = BigDecimal("1")), "mg/dL") {
                results.trySend(it)
            }
            assertEquals(null, withTimeout(5000) { results.receive() })
        } finally {
            scope.cancel()
        }
    }

    private class FakeGateway(
        private val load: (List<LabResult>) -> Map<String, LabReferenceRange?>,
        private val unitsAction: () -> Map<String, String> = { emptyMap() },
        private val saveAction: (String, LabReferenceRange?, String) -> Map<String, LabReferenceRange?> =
            { _, _, _ -> emptyMap() },
    ) : LabReferenceSettingsGateway {
        override fun initializeMissing(records: List<LabResult>) = load(records)
        override fun units() = unitsAction.invoke()
        override fun save(itemName: String, range: LabReferenceRange?, unit: String) =
            saveAction(itemName, range, unit)
    }
}
