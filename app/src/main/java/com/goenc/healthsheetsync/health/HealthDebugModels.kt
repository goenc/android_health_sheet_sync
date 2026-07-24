package com.goenc.healthsheetsync.health

import java.time.LocalDate
import java.time.LocalDateTime

data class HealthDebugUiState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.Checking,
    val permissions: PermissionState = PermissionState.Unknown,
    val isLoading: Boolean = true,
    val weightRecords: List<DebugWeightRecord> = emptyList(),
    val glucoseRecords: List<DebugGlucoseRecord> = emptyList(),
    val stepDailyRecords: List<DebugStepDaily> = emptyList(),
    val a1cDailyRecords: List<DebugA1cDaily> = emptyList(),
    val manualRecords: List<ManualHealthRecord> = emptyList(),
    val invalidatedGraphRecords: List<InvalidatedGraphRecord> = emptyList(),
    val yesterdaySteps: DebugStepDaily? = null,
    val sourceSummaries: List<String> = emptyList(),
    val debugMessages: List<String> = emptyList(),
    val dailyEnergySnapshots: List<DailyEnergySnapshot> = emptyList(),
) {
    val canRequestPermissions: Boolean
        get() = availability == HealthConnectAvailability.Available
}

sealed interface HealthConnectAvailability {
    data object Checking : HealthConnectAvailability
    data object Available : HealthConnectAvailability
    data class Unavailable(val reason: String) : HealthConnectAvailability
}

sealed interface PermissionState {
    data object Unknown : PermissionState
    data class Granted(val grantedCount: Int, val requiredCount: Int) : PermissionState
    data class Missing(
        val grantedCount: Int,
        val requiredCount: Int,
        val missingPermissions: List<String>,
    ) : PermissionState
}

data class DebugWeightRecord(
    val measuredAt: LocalDateTime,
    val targetDate: LocalDate,
    val timeBand: String,
    val weightKg: Double,
    val healthConnectId: String,
    val sourceAppName: String,
    val sourcePackageName: String,
)

data class DebugGlucoseRecord(
    val measuredAt: LocalDateTime,
    val targetDate: LocalDate,
    val timeBand: String,
    val bloodGlucoseMgDl: Double,
    val mealRelation: String,
    val healthConnectId: String,
    val sourceAppName: String,
    val sourcePackageName: String,
)

data class DebugStepDaily(
    val targetDate: LocalDate,
    val steps: Long,
    val aggregationStartAt: LocalDateTime,
    val aggregationEndAt: LocalDateTime,
)

data class DebugStepRecord(
    val healthConnectId: String,
    val targetDate: LocalDate,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val steps: Long,
)

data class DailyEnergySnapshot(
    val targetDate: LocalDate,
    val steps: Long,
    val basalMetabolicRate: Int,
    val pal: Double,
    val estimatedTotalKcal: Int,
    val finalizedAt: LocalDateTime,
)

data class DebugA1cDaily(
    val targetDate: LocalDate,
    val measuredAt: LocalDateTime,
    val a1cPercent: Double,
    val manualId: String,
)

enum class ManualRecordType(
    val label: String,
) {
    Weight("体重"),
    Steps("歩数"),
    BloodGlucose("血糖値"),
    BloodPressure("血圧"),
    Waist("腹囲"),
    A1c("A1c"),
}

data class ManualHealthRecord(
    val id: String,
    val type: ManualRecordType,
    val measuredAt: LocalDateTime,
    val valueText: String,
    val invalidatedAt: LocalDateTime?,
)

data class ManualHealthRecordDraft(
    val type: ManualRecordType,
    val measuredAt: LocalDateTime,
    val valueText: String,
)

data class InvalidatedGraphRecord(
    val recordType: String,
    val uniqueKey: String,
    val manualType: ManualRecordType,
    val measuredAt: LocalDateTime,
    val text: String,
    val invalidatedAt: LocalDateTime,
)
