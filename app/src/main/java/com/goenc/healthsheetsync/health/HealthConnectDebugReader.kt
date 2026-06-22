package com.goenc.healthsheetsync.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import com.goenc.healthsheetsync.widget.HealthGraphWidgetUpdater
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthConnectDebugReader(private val context: Context) {
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val localStore = LocalHealthDataStore(context)
    private val syncPreferences = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)
    private val appLabelCache = mutableMapOf<String, String>()

    suspend fun load(): HealthDebugUiState {
        val debugMessages = mutableListOf<String>()
        val sdkStatusCheck = checkSdkStatus()
        val availability = sdkStatusCheck.availability
        debugMessages += "HealthConnect SDK status: ${sdkStatusCheck.status.toSdkStatusLabel()} (${sdkStatusCheck.status})"
        if (availability !is HealthConnectAvailability.Available) {
            return HealthDebugUiState(
                availability = availability,
                permissions = PermissionState.Unknown,
                isLoading = false,
                manualRecords = localStore.load().manualRecords,
                invalidatedGraphRecords = localStore.load().invalidatedGraphRecords,
                debugMessages = debugMessages,
            )
        }

        val client = HealthConnectClient.getOrCreate(context)
        val grantedPermissions = runCatching {
            client.permissionController.getGrantedPermissions()
        }.getOrElse { error ->
            debugMessages += error.debugSummary("権限確認に失敗しました")
            emptySet()
        }
        val missingPermissions = REQUIRED_PERMISSIONS.minus(grantedPermissions).toList().sorted()
        val permissionState = if (missingPermissions.isEmpty()) {
            PermissionState.Granted(grantedPermissions.size, REQUIRED_PERMISSIONS.size)
        } else {
            PermissionState.Missing(
                grantedCount = REQUIRED_PERMISSIONS.size - missingPermissions.size,
                requiredCount = REQUIRED_PERMISSIONS.size,
                missingPermissions = missingPermissions,
            )
        }

        if (missingPermissions.isNotEmpty()) {
            return HealthDebugUiState(
                availability = availability,
                permissions = permissionState,
                isLoading = false,
                manualRecords = localStore.load().manualRecords,
                invalidatedGraphRecords = localStore.load().invalidatedGraphRecords,
                debugMessages = debugMessages,
            )
        }

        val syncResult = runCatching {
            synchronize(client)
        }.getOrElse { error ->
            debugMessages += error.debugSummary("Health Connect同期に失敗しました")
            SyncResult(changed = false, message = "保存済みデータを表示しています")
        }
        Log.d(TAG, syncResult.message)
        debugMessages += syncResult.message
        if (syncResult.changed) {
            HealthGraphWidgetUpdater.requestUpdate(context.applicationContext)
        }
        val storedData = localStore.load()
        debugMessages += "保存済み件数: 体重${storedData.weightRecords.size}件、血糖${storedData.glucoseRecords.size}件、歩数${storedData.stepDailyRecords.size}日"
        val yesterday = LocalDate.now(zoneId).minusDays(1)
        val yesterdaySteps = storedData.stepDailyRecords.firstOrNull { it.targetDate == yesterday }

        return HealthDebugUiState(
            availability = availability,
            permissions = permissionState,
            isLoading = false,
            weightRecords = storedData.weightRecords,
            glucoseRecords = storedData.glucoseRecords,
            stepDailyRecords = storedData.stepDailyRecords,
            a1cDailyRecords = storedData.a1cDailyRecords,
            manualRecords = storedData.manualRecords,
            invalidatedGraphRecords = storedData.invalidatedGraphRecords,
            yesterdaySteps = yesterdaySteps,
            sourceSummaries = buildSourceSummaries(storedData.weightRecords, storedData.glucoseRecords),
            debugMessages = debugMessages,
        )
    }

    private fun checkSdkStatus(): SdkStatusCheck {
        val sdkStatus = HealthConnectClient.getSdkStatus(
            context,
            HEALTH_CONNECT_PROVIDER_PACKAGE,
        )
        Log.d(TAG, "HealthConnectClient.getSdkStatus returned ${sdkStatus.toSdkStatusLabel()} ($sdkStatus)")
        val availability = when (sdkStatus) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.Unavailable("ヘルスコネクト提供元の更新が必要です。")
            HealthConnectClient.SDK_UNAVAILABLE ->
                HealthConnectAvailability.Unavailable("ヘルスコネクトを利用できないか、インストールされていません。")
            else -> HealthConnectAvailability.Unavailable("ヘルスコネクトの利用可否が不明です。")
        }
        return SdkStatusCheck(sdkStatus, availability)
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

    private suspend fun synchronize(client: HealthConnectClient): SyncResult {
        val changesToken = syncPreferences.getString(CHANGES_TOKEN_KEY, null)
        return if (changesToken == null) {
            replaceSnapshot(client, "初回完全同期")
        } else {
            applyChanges(client, changesToken)
        }
    }

    private suspend fun replaceSnapshot(client: HealthConnectClient, reason: String): SyncResult {
        val nextChangesToken = client.getChangesToken(
            ChangesTokenRequest(recordTypes = SYNCED_RECORD_TYPES),
        )
        val now = Instant.now()
        val weightRecords = readWeightRecords(client, Instant.EPOCH, now)
        val glucoseRecords = readGlucoseRecords(client, Instant.EPOCH, now)
        val stepRecords = readStepRecords(client, Instant.EPOCH, now)
        localStore.replaceHealthConnectSnapshot(weightRecords, glucoseRecords, stepRecords)
        saveChangesToken(nextChangesToken)
        return SyncResult(
            changed = true,
            message = "$reason: 体重${weightRecords.size}件、血糖${glucoseRecords.size}件、歩数${stepRecords.size}件",
        )
    }

    private suspend fun applyChanges(client: HealthConnectClient, initialToken: String): SyncResult {
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
                deletedRecordIds = page.deletedRecordIds,
            )
            changedCount += page.changeCount
            changesToken = response.nextChangesToken
            saveChangesToken(changesToken)
        } while (response.hasMore)
        return SyncResult(
            changed = changedCount > 0,
            message = "差分同期: ${changedCount}件の変更を反映",
        )
    }

    private fun List<androidx.health.connect.client.changes.Change>.toChangePage(): ChangePage {
        val weightRecords = linkedMapOf<String, DebugWeightRecord>()
        val glucoseRecords = linkedMapOf<String, DebugGlucoseRecord>()
        val stepRecords = linkedMapOf<String, DebugStepRecord>()
        val deletedRecordIds = linkedSetOf<String>()
        forEach { change ->
            when (change) {
                is DeletionChange -> {
                    deletedRecordIds += change.recordId
                    weightRecords.remove(change.recordId)
                    glucoseRecords.remove(change.recordId)
                    stepRecords.remove(change.recordId)
                }
                is UpsertionChange -> {
                    val recordId = change.record.metadata.id
                    deletedRecordIds -= recordId
                    when (val record = change.record) {
                        is WeightRecord -> weightRecords[recordId] = record.toDebugRecord()
                        is BloodGlucoseRecord -> glucoseRecords[recordId] = record.toDebugRecord()
                        is StepsRecord -> stepRecords[recordId] = record.toDebugRecord()
                    }
                }
            }
        }
        return ChangePage(
            weightRecords = weightRecords.values.toList(),
            glucoseRecords = glucoseRecords.values.toList(),
            stepRecords = stepRecords.values.toList(),
            deletedRecordIds = deletedRecordIds,
            changeCount = size,
        )
    }

    private fun saveChangesToken(changesToken: String) {
        check(syncPreferences.edit().putString(CHANGES_TOKEN_KEY, changesToken).commit()) {
            "変更トークンを保存できませんでした"
        }
    }

    private fun WeightRecord.toDebugRecord(): DebugWeightRecord {
        val measuredAt = time.toLocalDateTime()
        val sourcePackage = metadata.dataOrigin.packageName.ifBlank { UNKNOWN }
        return DebugWeightRecord(
            measuredAt = measuredAt,
            targetDate = measuredAt.toLocalDate(),
            timeBand = measuredAt.toTimeBand(),
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
            timeBand = measuredAt.toTimeBand(),
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

    private fun buildSourceSummaries(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
    ): List<String> {
        val sources = weightRecords.map { it.sourceAppName to it.sourcePackageName } +
            glucoseRecords.map { it.sourceAppName to it.sourcePackageName }
        return sources.distinct().map { (name, packageName) ->
            "$name / $packageName"
        }.ifEmpty {
            listOf("$UNKNOWN / $UNKNOWN")
        }
    }

    private fun Instant.toLocalDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(this, zoneId)

    private fun LocalDateTime.toTimeBand(): String {
        val hour = hour
        return when (hour) {
            in 4..11 -> "朝"
            in 12..17 -> "昼"
            else -> "夜"
        }
    }

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

    private fun Throwable.debugSummary(prefix: String): String {
        return "$prefix: ${message ?: "詳細なし"}"
    }

    private fun Int.toSdkStatusLabel(): String {
        return when (this) {
            HealthConnectClient.SDK_AVAILABLE -> "SDK_AVAILABLE"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                "SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED"
            HealthConnectClient.SDK_UNAVAILABLE -> "SDK_UNAVAILABLE"
            else -> "UNKNOWN"
        }
    }

    private data class SdkStatusCheck(
        val status: Int,
        val availability: HealthConnectAvailability,
    )

    private data class SyncResult(
        val changed: Boolean,
        val message: String,
    )

    private data class ChangePage(
        val weightRecords: List<DebugWeightRecord>,
        val glucoseRecords: List<DebugGlucoseRecord>,
        val stepRecords: List<DebugStepRecord>,
        val deletedRecordIds: Set<String>,
        val changeCount: Int,
    )

    companion object {
        const val UNKNOWN = "不明"
        private const val TAG = "HealthSheetSync"
        private const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val PAGE_SIZE = 1_000
        private const val SYNC_PREFERENCES = "health_connect_sync"
        private const val CHANGES_TOKEN_KEY = "changes_token_v1"

        private val SYNCED_RECORD_TYPES: Set<KClass<out Record>> = setOf(
            WeightRecord::class,
            BloodGlucoseRecord::class,
            StepsRecord::class,
        )

        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        )

        fun permissionRequestContract() =
            PermissionController.createRequestPermissionResultContract()
    }
}
