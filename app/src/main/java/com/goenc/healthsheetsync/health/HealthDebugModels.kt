package com.goenc.healthsheetsync.health

import java.time.LocalDate
import java.time.LocalDateTime

data class HealthDebugUiState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.Checking,
    val permissions: PermissionState = PermissionState.Unknown,
    val isLoading: Boolean = false,
    val weightRecords: List<DebugWeightRecord> = emptyList(),
    val glucoseRecords: List<DebugGlucoseRecord> = emptyList(),
    val yesterdaySteps: DebugStepDaily? = null,
    val sourceSummaries: List<String> = emptyList(),
    val debugMessages: List<String> = emptyList(),
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
