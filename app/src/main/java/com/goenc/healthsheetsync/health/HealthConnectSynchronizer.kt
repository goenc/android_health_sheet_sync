package com.goenc.healthsheetsync.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import java.time.Instant
import kotlin.reflect.KClass

internal class HealthConnectSynchronizer(
    private val context: Context,
    private val localStore: LocalHealthDataStore,
) {
    private val recordMapper = HealthConnectRecordMapper(context)
    private val syncPreferences = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)

    suspend fun synchronize(client: HealthConnectClient): HealthConnectSyncResult {
        val changesToken = syncPreferences.getString(CHANGES_TOKEN_KEY, null)
        return if (changesToken == null) {
            replaceSnapshot(client, "初回完全同期")
        } else {
            applyChanges(client, changesToken)
        }
    }

    private suspend fun replaceSnapshot(
        client: HealthConnectClient,
        reason: String,
    ): HealthConnectSyncResult {
        val nextChangesToken = client.getChangesToken(
            ChangesTokenRequest(recordTypes = SYNCED_RECORD_TYPES),
        )
        val now = Instant.now()
        val weightRecords = readWeightRecords(client, Instant.EPOCH, now)
        val glucoseRecords = readGlucoseRecords(client, Instant.EPOCH, now)
        val stepRecords = readStepRecords(client, Instant.EPOCH, now)
        val distanceRecords = readDistanceRecords(client, Instant.EPOCH, now)
        localStore.replaceHealthConnectSnapshot(
            weightRecords = weightRecords,
            glucoseRecords = glucoseRecords,
            stepRecords = stepRecords,
            distanceRecords = distanceRecords,
        )
        saveChangesToken(nextChangesToken)
        return HealthConnectSyncResult(
            changed = true,
            message = "$reason: 体重${weightRecords.size}件、血糖${glucoseRecords.size}件、歩数${stepRecords.size}件、距離${distanceRecords.size}件",
        )
    }

    private suspend fun applyChanges(
        client: HealthConnectClient,
        initialToken: String,
    ): HealthConnectSyncResult {
        var changesToken = initialToken
        var changedCount = 0
        do {
            val response = client.getChanges(changesToken, PAGE_SIZE)
            if (response.changesTokenExpired) {
                return replaceSnapshot(client, "変更トークン失効による完全同期")
            }
            val page = recordMapper.changePage(response.changes)
            localStore.applyHealthConnectChanges(
                weightRecords = page.weightRecords,
                glucoseRecords = page.glucoseRecords,
                stepRecords = page.stepRecords,
                distanceRecords = page.distanceRecords,
                deletedRecordIds = page.deletedRecordIds,
            )
            changedCount += page.changeCount
            changesToken = response.nextChangesToken
            saveChangesToken(changesToken)
        } while (response.hasMore)
        return HealthConnectSyncResult(
            changed = changedCount > 0,
            message = "差分同期: ${changedCount}件の変更を反映",
        )
    }

    private suspend fun readWeightRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugWeightRecord> {
        return client.readAllRecords(WeightRecord::class, start, end)
            .sortedByDescending { it.time }
            .map { record -> recordMapper.map(record) }
    }

    private suspend fun readGlucoseRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugGlucoseRecord> {
        return client.readAllRecords(BloodGlucoseRecord::class, start, end)
            .sortedByDescending { it.time }
            .map { record -> recordMapper.map(record) }
    }

    private suspend fun readStepRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugStepRecord> {
        return client.readAllRecords(StepsRecord::class, start, end)
            .map { record -> recordMapper.map(record) }
    }

    private suspend fun readDistanceRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugDistanceRecord> {
        return client.readAllRecords(DistanceRecord::class, start, end)
            .map { record -> recordMapper.map(record) }
    }

    private suspend fun <T : Record> HealthConnectClient.readAllRecords(
        recordType: KClass<T>,
        start: Instant,
        end: Instant,
    ): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrBlank())
        return records
    }

    private fun saveChangesToken(changesToken: String) {
        check(syncPreferences.edit().putString(CHANGES_TOKEN_KEY, changesToken).commit()) {
            "変更トークンを保存できませんでした"
        }
    }

    private companion object {
        private const val PAGE_SIZE = 1_000
        private const val SYNC_PREFERENCES = "health_connect_sync"
        private const val CHANGES_TOKEN_KEY = "changes_token_v2"

        private val SYNCED_RECORD_TYPES: Set<KClass<out Record>> = setOf(
            WeightRecord::class,
            BloodGlucoseRecord::class,
            StepsRecord::class,
            DistanceRecord::class,
        )
    }
}

internal data class HealthConnectSyncResult(
    val changed: Boolean,
    val message: String,
)
