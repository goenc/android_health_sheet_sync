package com.goenc.healthsheetsync.health

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthRecordIdentityTest {
    @Test
    fun usesHealthConnectIdWhenAvailable() {
        assertEquals("weight|record-1", weightRecord(healthConnectId = "record-1").healthRecordUniqueKey())
        assertEquals("glucose|record-2", glucoseRecord(healthConnectId = "record-2").healthRecordUniqueKey())
    }

    @Test
    fun fallsBackToStableRecordFieldsWhenHealthConnectIdIsUnavailable() {
        assertEquals(
            "weight|2026-08-11T08:30|source.package|72.5",
            weightRecord(healthConnectId = "不明").healthRecordUniqueKey(),
        )
        assertEquals(
            "glucose|2026-08-11T08:30|source.package|110.0|空腹時",
            glucoseRecord(healthConnectId = "").healthRecordUniqueKey(),
        )
    }

    @Test
    fun rejectsUnavailableHealthConnectIds() {
        assertNull(stableHealthRecordKey("weight", ""))
        assertNull(stableHealthRecordKey("weight", "不明"))
    }

    private fun weightRecord(healthConnectId: String): DebugWeightRecord {
        val measuredAt = LocalDateTime.of(2026, 8, 11, 8, 30)
        return DebugWeightRecord(
            measuredAt = measuredAt,
            targetDate = measuredAt.toLocalDate(),
            timeBand = "朝",
            weightKg = 72.5,
            healthConnectId = healthConnectId,
            sourceAppName = "Source",
            sourcePackageName = "source.package",
        )
    }

    private fun glucoseRecord(healthConnectId: String): DebugGlucoseRecord {
        val measuredAt = LocalDateTime.of(2026, 8, 11, 8, 30)
        return DebugGlucoseRecord(
            measuredAt = measuredAt,
            targetDate = measuredAt.toLocalDate(),
            timeBand = "朝",
            bloodGlucoseMgDl = 110.0,
            mealRelation = "空腹時",
            healthConnectId = healthConnectId,
            sourceAppName = "Source",
            sourcePackageName = "source.package",
        )
    }
}
