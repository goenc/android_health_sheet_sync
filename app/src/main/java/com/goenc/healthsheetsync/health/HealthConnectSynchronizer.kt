package com.goenc.healthsheetsync.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
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
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.reflect.KClass

internal class HealthConnectSynchronizer(
    private val context: Context,
    private val localStore: LocalHealthDataStore,
) {
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val syncPreferences = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)
    private val appLabelCache = mutableMapOf<String, String>()

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
            val page = response.changes.toChangePage()
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

    private fun List<Change>.toChangePage(): ChangePage {
        val weightRecords = linkedMapOf<String, DebugWeightRecord>()
        val glucoseRecords = linkedMapOf<String, DebugGlucoseRecord>()
        val stepRecords = linkedMapOf<String, DebugStepRecord>()
        val distanceRecords = linkedMapOf<String, DebugDistanceRecord>()
        val deletedRecordIds = linkedSetOf<String>()
        forEach { change ->
            when (change) {
                is DeletionChange -> {
                    deletedRecordIds += change.recordId
                    weightRecords.remove(change.recordId)
                    glucoseRecords.remove(change.recordId)
                    stepRecords.remove(change.recordId)
                    distanceRecords.remove(change.recordId)
                }
                is UpsertionChange -> {
                    val recordId = change.record.metadata.id
                    deletedRecordIds -= recordId
                    when (val record = change.record) {
                        is WeightRecord -> weightRecords[recordId] = record.toDebugRecord()
                        is BloodGlucoseRecord -> glucoseRecords[recordId] = record.toDebugRecord()
                        is StepsRecord -> stepRecords[recordId] = record.toDebugRecord()
                        is DistanceRecord -> distanceRecords[recordId] = record.toDebugRecord()
                    }
                }
            }
        }
        return ChangePage(
            weightRecords = weightRecords.values.toList(),
            glucoseRecords = glucoseRecords.values.toList(),
            stepRecords = stepRecords.values.toList(),
            distanceRecords = distanceRecords.values.toList(),
            deletedRecordIds = deletedRecordIds,
            changeCount = size,
        )
    }

    private suspend fun readWeightRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugWeightRecord> {
        return client.readAllRecords(WeightRecord::class, start, end)
            .sortedByDescending { it.time }
            .map { record -> record.toDebugRecord() }
    }

    private suspend fun readGlucoseRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugGlucoseRecord> {
        return client.readAllRecords(BloodGlucoseRecord::class, start, end)
            .sortedByDescending { it.time }
            .map { record -> record.toDebugRecord() }
    }

    private suspend fun readStepRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugStepRecord> {
        return client.readAllRecords(StepsRecord::class, start, end)
            .map { record -> record.toDebugRecord() }
    }

    private suspend fun readDistanceRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugDistanceRecord> {
        return client.readAllRecords(DistanceRecord::class, start, end)
            .map { record -> record.toDebugRecord() }
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

    private fun WeightRecord.toDebugRecord(): DebugWeightRecord {
        val measuredAt = time.toLocalDateTime()
        val sourcePackage = metadata.dataOrigin.packageName.ifBlank { UNKNOWN }
        return DebugWeightRecord(
            measuredAt = measuredAt,
            targetDate = measuredAt.toLocalDate(),
            timeBand = measuredAt.toHealthTimeBand(),
            weightKg = weight.inKilograms,
            healthConnectId = metadata.id.ifBlank { UNKNOWN },
            sourceAppName = sourcePackage.toAppLabel(),
            sourcePackageName = sourcePackage,
        )
    }

    private fun BloodGlucoseRecord.toDebugRecord(): DebugGlucoseRecord {
        val measuredAt = time.toLocalDateTime()
        val sourcePackage = metadata.dataOrigin.packageName.ifBlank { UNKNOWN }
        return DebugGlucoseRecord(
            measuredAt = measuredAt,
            targetDate = measuredAt.toLocalDate(),
            timeBand = measuredAt.toHealthTimeBand(),
            bloodGlucoseMgDl = level.inMilligramsPerDeciliter,
            mealRelation = relationToMeal.toMealRelation(),
            healthConnectId = metadata.id.ifBlank { UNKNOWN },
            sourceAppName = sourcePackage.toAppLabel(),
            sourcePackageName = sourcePackage,
        )
    }

    private fun StepsRecord.toDebugRecord(): DebugStepRecord {
        val startAt = startTime.toLocalDateTime()
        return DebugStepRecord(
            healthConnectId = metadata.id.ifBlank { UNKNOWN },
            targetDate = startAt.toLocalDate(),
            startAt = startAt,
            endAt = endTime.toLocalDateTime(),
            steps = count,
        )
    }

    private fun DistanceRecord.toDebugRecord(): DebugDistanceRecord {
        val startAt = startTime.toLocalDateTime()
        return DebugDistanceRecord(
            healthConnectId = metadata.id.ifBlank { UNKNOWN },
            targetDate = startAt.toLocalDate(),
            startAt = startAt,
            endAt = endTime.toLocalDateTime(),
            distanceMeters = distance.inMeters,
        )
    }

    private fun saveChangesToken(changesToken: String) {
        check(syncPreferences.edit().putString(CHANGES_TOKEN_KEY, changesToken).commit()) {
            "変更トークンを保存できませんでした"
        }
    }

    private fun Instant.toLocalDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(this, zoneId)

    private fun Int.toMealRelation(): String {
        return when (this) {
            BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL -> "通常"
            BloodGlucoseRecord.RELATION_TO_MEAL_FASTING -> "空腹時"
            BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL -> "食前"
            BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL -> "食後"
            else -> UNKNOWN
        }
    }

    private fun String.toAppLabel(): String {
        if (this == UNKNOWN) return UNKNOWN
        return appLabelCache.getOrPut(this) {
            runCatching {
                val appInfo = context.packageManager.getApplicationInfo(this, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            }.getOrElse { UNKNOWN }
        }
    }

    private data class ChangePage(
        val weightRecords: List<DebugWeightRecord>,
        val glucoseRecords: List<DebugGlucoseRecord>,
        val stepRecords: List<DebugStepRecord>,
        val distanceRecords: List<DebugDistanceRecord>,
        val deletedRecordIds: Set<String>,
        val changeCount: Int,
    )

    private companion object {
        private const val UNKNOWN = "不明"
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
