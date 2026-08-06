package com.goenc.healthsheetsync.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import com.goenc.healthsheetsync.widget.HealthGraphWidgetUpdater
import java.time.LocalDate
import java.time.ZoneId

class HealthConnectDebugReader(private val context: Context) {
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val localStore = LocalHealthDataStore(context)
    private val synchronizer = HealthConnectSynchronizer(context, localStore)

    suspend fun load(basalMetabolicRate: Int): HealthDebugUiState {
        val debugMessages = mutableListOf<String>()
        val sdkStatusCheck = checkSdkStatus()
        val availability = sdkStatusCheck.availability
        debugMessages += "HealthConnect SDK status: ${sdkStatusCheck.status.toSdkStatusLabel()} (${sdkStatusCheck.status})"
        if (availability !is HealthConnectAvailability.Available) {
            val storedData = localStore.load()
            return HealthDebugUiState(
                availability = availability,
                permissions = PermissionState.Unknown,
                isLoading = false,
                manualRecords = storedData.manualRecords,
                invalidatedGraphRecords = storedData.invalidatedGraphRecords,
                dailyEnergySnapshots = storedData.dailyEnergySnapshots,
                dailyBodySettings = storedData.dailyBodySettings,
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
            val storedData = localStore.load()
            return HealthDebugUiState(
                availability = availability,
                permissions = permissionState,
                isLoading = false,
                manualRecords = storedData.manualRecords,
                invalidatedGraphRecords = storedData.invalidatedGraphRecords,
                dailyEnergySnapshots = storedData.dailyEnergySnapshots,
                dailyBodySettings = storedData.dailyBodySettings,
                debugMessages = debugMessages,
            )
        }

        val syncAttempt = runCatching {
            synchronizer.synchronize(client)
        }
        val syncResult = syncAttempt.getOrElse { error ->
            debugMessages += error.debugSummary("Health Connect同期に失敗しました")
            HealthConnectSyncResult(changed = false, message = "保存済みデータを表示しています")
        }
        Log.d(TAG, syncResult.message)
        debugMessages += syncResult.message
        if (syncResult.changed) {
            HealthGraphWidgetUpdater.requestUpdate(context.applicationContext)
        }
        if (shouldFinalizePastDailyEnergySnapshotsAfterHealthConnectSync(
                availability = availability,
                hasMissingPermissions = missingPermissions.isNotEmpty(),
                synchronizationSucceeded = syncAttempt.isSuccess,
            )
        ) {
            val repairedCount = localStore.repairLegacySyncDailyEnergySnapshots()
            if (repairedCount > 0) {
                debugMessages += "同期前確定の推定総消費を${repairedCount}日修復"
            }
            localStore.finalizeHealthConnectDailyEnergySnapshotsAfterSync(basalMetabolicRate)
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
            dailyEnergySnapshots = storedData.dailyEnergySnapshots,
            dailyBodySettings = storedData.dailyBodySettings,
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

    companion object {
        const val UNKNOWN = "不明"
        private const val TAG = "HealthSheetSync"
        private const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

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

internal fun shouldFinalizePastDailyEnergySnapshotsAfterHealthConnectSync(
    availability: HealthConnectAvailability,
    hasMissingPermissions: Boolean,
    synchronizationSucceeded: Boolean,
): Boolean {
    return availability is HealthConnectAvailability.Available &&
        !hasMissingPermissions &&
        synchronizationSucceeded
}
