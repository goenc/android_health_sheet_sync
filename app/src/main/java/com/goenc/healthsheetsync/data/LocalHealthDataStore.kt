package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugStepRecord
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.DailyEnergySnapshot
import com.goenc.healthsheetsync.health.InvalidatedGraphRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import java.time.LocalDateTime

class LocalHealthDataStore(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.createHealthConnectTables()
        db.createManualRecordsTable()
        db.createA1cDailyRecordsTable()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.createManualRecordsTable()
        }
        if (oldVersion < 3) {
            db.createInvalidatedRecordsTable()
        }
        if (oldVersion < 4) {
            db.createA1cDailyRecordsTable()
            backfillA1cDailyRecords(db)
        }
        if (oldVersion < 5) {
            db.createHealthConnectStepRecordsTable()
        }
        if (oldVersion < 6) {
            db.createDailyEnergySnapshotsTable()
        }
        if (oldVersion < 7) {
            db.createDailyEnergyRepairsTable()
        }
    }

    fun save(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepDailyRecords: List<DebugStepDaily>,
    ) {
        val updatedAt = LocalDateTime.now().toString()
        writableDatabase.runInTransaction {
            val healthConnectStore = HealthConnectRecordStore(this)
            healthConnectStore.saveWeightRecords(weightRecords, updatedAt)
            healthConnectStore.saveGlucoseRecords(glucoseRecords, updatedAt)
            stepDailyRecords.forEach { record ->
                replace(
                    TABLE_STEPS,
                    null,
                    ContentValues().apply {
                        put("target_date", record.targetDate.toString())
                        put("steps", record.steps)
                        put("aggregation_start_at", record.aggregationStartAt.toString())
                        put("aggregation_end_at", record.aggregationEndAt.toString())
                        put("updated_at", updatedAt)
                    },
                )
            }
        }
    }

    fun replaceHealthConnectSnapshot(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepRecords: List<DebugStepRecord>,
    ) {
        HealthConnectRecordStore(writableDatabase).replaceSnapshot(
            weightRecords = weightRecords,
            glucoseRecords = glucoseRecords,
            stepRecords = stepRecords,
        )
    }

    fun applyHealthConnectChanges(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepRecords: List<DebugStepRecord>,
        deletedRecordIds: Set<String>,
    ) {
        HealthConnectRecordStore(writableDatabase).applyChanges(
            weightRecords = weightRecords,
            glucoseRecords = glucoseRecords,
            stepRecords = stepRecords,
            deletedRecordIds = deletedRecordIds,
        )
    }

    fun load(): StoredHealthData = HealthRecordQueries(readableDatabase).load()

    fun finalizeHealthConnectDailyEnergySnapshotsAfterSync(basalMetabolicRate: Int) {
        DailyEnergySnapshotStore(writableDatabase).finalizeHealthConnectPastDays(basalMetabolicRate)
    }

    fun finalizeManualStepsForDate(
        targetDate: LocalDate,
        steps: Long,
        basalMetabolicRate: Int,
    ): Boolean {
        return DailyEnergySnapshotStore(writableDatabase).finalizeManualStepsForDate(
            targetDate = targetDate,
            steps = steps,
            basalMetabolicRate = basalMetabolicRate,
        )
    }

    fun finalizeManualOnlyPastDaysIfSafe(basalMetabolicRate: Int): Boolean {
        return DailyEnergySnapshotStore(writableDatabase).finalizeManualOnlyPastDaysIfSafe(basalMetabolicRate)
    }

    fun repairLegacySyncDailyEnergySnapshots(): Int {
        return DailyEnergySnapshotStore(writableDatabase).repairLegacySyncSnapshots()
    }

    fun saveManualRecord(draft: ManualHealthRecordDraft) {
        ManualRecordRepository(writableDatabase).save(draft)
    }

    fun invalidateManualRecord(id: String) {
        writableDatabase.update(
            TABLE_MANUAL,
            ContentValues().apply {
                put("invalidated_at", LocalDateTime.now().toString())
                put("updated_at", LocalDateTime.now().toString())
            },
            "id = ? AND invalidated_at IS NULL",
            arrayOf(id),
        )
    }

    fun restoreManualRecord(id: String) {
        writableDatabase.update(
            TABLE_MANUAL,
            ContentValues().apply {
                putNull("invalidated_at")
                put("updated_at", LocalDateTime.now().toString())
            },
            "id = ?",
            arrayOf(id),
        )
    }

    fun deleteManualRecord(id: String) {
        writableDatabase.runInTransaction {
            delete(TABLE_A1C_DAILY, "manual_id = ?", arrayOf(id))
            delete(TABLE_MANUAL, "id = ?", arrayOf(id))
        }
    }

    fun invalidateStoredRecord(recordType: String, uniqueKey: String) {
        writableDatabase.replace(
            TABLE_INVALIDATED,
            null,
            ContentValues().apply {
                put("record_type", recordType)
                put("unique_key", uniqueKey)
                put("invalidated_at", LocalDateTime.now().toString())
            },
        )
    }

    fun restoreStoredRecord(recordType: String, uniqueKey: String) {
        writableDatabase.delete(
            TABLE_INVALIDATED,
            "record_type = ? AND unique_key = ?",
            arrayOf(recordType, uniqueKey),
        )
    }

    fun deleteStoredRecord(recordType: String, uniqueKey: String) {
        writableDatabase.runInTransaction {
            delete(
                TABLE_INVALIDATED,
                "record_type = ? AND unique_key = ?",
                arrayOf(recordType, uniqueKey),
            )
            when (recordType) {
                "weight" -> delete(TABLE_WEIGHT, "unique_key = ?", arrayOf(uniqueKey))
                "glucose" -> delete(TABLE_GLUCOSE, "unique_key = ?", arrayOf(uniqueKey))
                "steps" -> delete(TABLE_STEPS, "target_date = ?", arrayOf(uniqueKey))
            }
        }
    }

    private fun backfillA1cDailyRecords(db: SQLiteDatabase) {
        val now = LocalDateTime.now().toString()
        db.rawQuery(
            """
            SELECT id, measured_at, value_text
            FROM manual_records
            WHERE type = ?
            ORDER BY measured_at ASC
            """.trimIndent(),
            arrayOf(ManualRecordType.A1c.name),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val measuredAt = LocalDateTime.parse(cursor.getString(1))
                val a1cPercent = cursor.getString(2).removeSuffix(" %").toDoubleOrNull() ?: continue
                db.replace(
                    TABLE_A1C_DAILY,
                    null,
                    ContentValues().apply {
                        put("target_date", measuredAt.toLocalDate().toString())
                        put("measured_at", measuredAt.toString())
                        put("a1c_percent", a1cPercent)
                        put("manual_id", cursor.getString(0))
                        put("updated_at", now)
                    },
                )
            }
        }
    }

}

data class StoredHealthData(
    val weightRecords: List<DebugWeightRecord>,
    val glucoseRecords: List<DebugGlucoseRecord>,
    val stepDailyRecords: List<DebugStepDaily>,
    val a1cDailyRecords: List<DebugA1cDaily>,
    val manualRecords: List<ManualHealthRecord>,
    val invalidatedGraphRecords: List<InvalidatedGraphRecord>,
    val dailyEnergySnapshots: List<DailyEnergySnapshot>,
)
