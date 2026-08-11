package com.goenc.healthsheetsync.health

internal fun DebugWeightRecord.healthRecordUniqueKey(): String {
    return stableHealthRecordKey("weight", healthConnectId)
        ?: "weight|$measuredAt|$sourcePackageName|$weightKg"
}

internal fun DebugGlucoseRecord.healthRecordUniqueKey(): String {
    return stableHealthRecordKey("glucose", healthConnectId)
        ?: "glucose|$measuredAt|$sourcePackageName|$bloodGlucoseMgDl|$mealRelation"
}

internal fun stableHealthRecordKey(recordType: String, healthConnectId: String): String? {
    if (healthConnectId.isBlank() || healthConnectId == UNKNOWN_HEALTH_CONNECT_ID) return null
    return "$recordType|$healthConnectId"
}

private const val UNKNOWN_HEALTH_CONNECT_ID = "不明"
