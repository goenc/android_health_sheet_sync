package com.goenc.healthsheetsync.health

import android.content.Context
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal class HealthConnectRecordMapper(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val appLabelCache = mutableMapOf<String, String>()

    fun changePage(changes: List<Change>): HealthConnectChangePage = changes.toChangePage()

    fun map(record: WeightRecord): DebugWeightRecord = record.toDebugRecord()
    fun map(record: BloodGlucoseRecord): DebugGlucoseRecord = record.toDebugRecord()
    fun map(record: StepsRecord): DebugStepRecord = record.toDebugRecord()
    fun map(record: DistanceRecord): DebugDistanceRecord = record.toDebugRecord()

    private fun List<Change>.toChangePage(): HealthConnectChangePage {
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
        return HealthConnectChangePage(
            weightRecords = weightRecords.values.toList(),
            glucoseRecords = glucoseRecords.values.toList(),
            stepRecords = stepRecords.values.toList(),
            distanceRecords = distanceRecords.values.toList(),
            deletedRecordIds = deletedRecordIds,
            changeCount = size,
        )
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

    private companion object {
        private const val UNKNOWN = "不明"
    }
}

internal data class HealthConnectChangePage(
    val weightRecords: List<DebugWeightRecord>,
    val glucoseRecords: List<DebugGlucoseRecord>,
    val stepRecords: List<DebugStepRecord>,
    val distanceRecords: List<DebugDistanceRecord>,
    val deletedRecordIds: Set<String>,
    val changeCount: Int,
)
