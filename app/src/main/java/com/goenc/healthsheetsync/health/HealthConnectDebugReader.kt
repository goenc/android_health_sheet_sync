package com.goenc.healthsheetsync.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthConnectDebugReader(private val context: Context) {
    private val zoneId: ZoneId = ZoneId.systemDefault()

    suspend fun load(): HealthDebugUiState {
        val debugMessages = mutableListOf<String>()
        val availability = checkAvailability()
        if (availability !is HealthConnectAvailability.Available) {
            return HealthDebugUiState(
                availability = availability,
                permissions = PermissionState.Unknown,
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
                debugMessages = debugMessages,
            )
        }

        val now = Instant.now()
        val readStart = now.minus(Duration.ofDays(30))
        debugMessages += "読み取り範囲: ${readStart.toLocalDateTime()} - ${now.toLocalDateTime()}"

        val weightRecords = runCatching {
            readWeightRecords(client, readStart, now)
        }.getOrElse { error ->
            debugMessages += error.debugSummary("体重記録の読み取りに失敗しました")
            emptyList()
        }
        val glucoseRecords = runCatching {
            readGlucoseRecords(client, readStart, now)
        }.getOrElse { error ->
            debugMessages += error.debugSummary("血糖値記録の読み取りに失敗しました")
            emptyList()
        }
        val yesterdaySteps = runCatching {
            readYesterdaySteps(client)
        }.getOrElse { error ->
            debugMessages += error.debugSummary("歩数集計に失敗しました")
            null
        }

        return HealthDebugUiState(
            availability = availability,
            permissions = permissionState,
            weightRecords = weightRecords,
            glucoseRecords = glucoseRecords,
            yesterdaySteps = yesterdaySteps,
            sourceSummaries = buildSourceSummaries(weightRecords, glucoseRecords),
            debugMessages = debugMessages,
        )
    }

    private fun checkAvailability(): HealthConnectAvailability {
        return when (
            HealthConnectClient.getSdkStatus(
                context,
                HEALTH_CONNECT_PROVIDER_PACKAGE,
            )
        ) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.Unavailable("ヘルスコネクト提供元の更新が必要です。")
            HealthConnectClient.SDK_UNAVAILABLE ->
                HealthConnectAvailability.Unavailable("ヘルスコネクトを利用できないか、インストールされていません。")
            else -> HealthConnectAvailability.Unavailable("ヘルスコネクトの利用可否が不明です。")
        }
    }

    private suspend fun readWeightRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugWeightRecord> {
        return client.readAllRecords(WeightRecord::class, start, end)
            .sortedByDescending { it.time }
            .map { record ->
                val measuredAt = record.time.toLocalDateTime()
                val sourcePackage = record.metadata.dataOrigin.packageName.ifBlank { UNKNOWN }
                DebugWeightRecord(
                    measuredAt = measuredAt,
                    targetDate = measuredAt.toLocalDate(),
                    timeBand = measuredAt.toTimeBand(),
                    weightKg = record.weight.inKilograms,
                    healthConnectId = record.metadata.id.ifBlank { UNKNOWN },
                    sourceAppName = sourcePackage.toAppLabel(),
                    sourcePackageName = sourcePackage,
                )
            }
    }

    private suspend fun readGlucoseRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<DebugGlucoseRecord> {
        return client.readAllRecords(BloodGlucoseRecord::class, start, end)
            .sortedByDescending { it.time }
            .map { record ->
                val measuredAt = record.time.toLocalDateTime()
                val sourcePackage = record.metadata.dataOrigin.packageName.ifBlank { UNKNOWN }
                DebugGlucoseRecord(
                    measuredAt = measuredAt,
                    targetDate = measuredAt.toLocalDate(),
                    timeBand = measuredAt.toTimeBand(),
                    bloodGlucoseMgDl = record.level.inMilligramsPerDeciliter,
                    mealRelation = record.relationToMeal.toMealRelation(),
                    healthConnectId = record.metadata.id.ifBlank { UNKNOWN },
                    sourceAppName = sourcePackage.toAppLabel(),
                    sourcePackageName = sourcePackage,
                )
            }
    }

    private suspend fun readYesterdaySteps(client: HealthConnectClient): DebugStepDaily {
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)
        val startAt = yesterday.atStartOfDay()
        val endAt = today.atStartOfDay()
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    startAt.atZone(zoneId).toInstant(),
                    endAt.atZone(zoneId).toInstant(),
                ),
            ),
        )
        return DebugStepDaily(
            targetDate = yesterday,
            steps = response[StepsRecord.COUNT_TOTAL] ?: 0L,
            aggregationStartAt = startAt,
            aggregationEndAt = endAt,
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
        return runCatching {
            val appInfo = context.packageManager.getApplicationInfo(this, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrElse { UNKNOWN }
    }

    private fun Throwable.debugSummary(prefix: String): String {
        return "$prefix: ${message ?: "詳細なし"}"
    }

    companion object {
        const val UNKNOWN = "不明"
        private const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val PAGE_SIZE = 1_000

        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )

        fun permissionRequestContract() =
            PermissionController.createRequestPermissionResultContract()
    }
}
