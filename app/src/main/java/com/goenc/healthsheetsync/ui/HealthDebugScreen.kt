package com.goenc.healthsheetsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.HealthConnectAvailability
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.PermissionState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun HealthDebugScreen(
    state: HealthDebugUiState,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Health Sheet Sync",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        DebugSection(title = "Health Connect") {
            DebugLine("Availability", state.availability.displayText())
            DebugLine("Permissions", state.permissions.displayText())
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onRequestPermissions,
                enabled = state.canRequestPermissions && !state.isLoading,
            ) {
                Text("Request Permissions")
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
            ) {
                Text(if (state.isLoading) "Loading" else "Refresh Data")
            }
        }

        DebugSection(title = "WeightRecord") {
            DebugLine("Count", state.weightRecords.size.toString())
            state.weightRecords.take(10).forEach { record ->
                WeightRecordRow(record)
            }
        }

        DebugSection(title = "BloodGlucoseRecord") {
            DebugLine("Count", state.glucoseRecords.size.toString())
            state.glucoseRecords.take(10).forEach { record ->
                GlucoseRecordRow(record)
            }
        }

        DebugSection(title = "Yesterday Steps") {
            val steps = state.yesterdaySteps
            DebugLine("Steps", "${steps?.steps ?: 0} steps")
            DebugLine("Aggregation Start", steps?.aggregationStartAt?.formatDateTime() ?: "unknown")
            DebugLine("Aggregation End", steps?.aggregationEndAt?.formatDateTime() ?: "unknown")
        }

        DebugSection(title = "Debug") {
            state.sourceSummaries.forEach { source ->
                DebugLine("Source", source)
            }
            state.debugMessages.forEach { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DebugSection(
    title: String,
    content: @Composable ColumnScopeMarker.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ColumnScopeMarker.content()
        HorizontalDivider()
    }
}

private object ColumnScopeMarker

@Composable
private fun DebugLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WeightRecordRow(record: DebugWeightRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${record.measuredAt.formatDateTime()} / ${record.timeBand}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("${formatDecimal(record.weightKg)} kg / target ${record.targetDate}")
        Text("id ${record.healthConnectId}")
        Text("source ${record.sourceAppName} / ${record.sourcePackageName}")
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun GlucoseRecordRow(record: DebugGlucoseRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${record.measuredAt.formatDateTime()} / ${record.timeBand}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("${formatDecimal(record.bloodGlucoseMgDl)} mg/dL / ${record.mealRelation}")
        Text("target ${record.targetDate}")
        Text("id ${record.healthConnectId}")
        Text("source ${record.sourceAppName} / ${record.sourcePackageName}")
        Spacer(Modifier.height(4.dp))
    }
}

private fun HealthConnectAvailability.displayText(): String {
    return when (this) {
        HealthConnectAvailability.Checking -> "checking"
        HealthConnectAvailability.Available -> "available"
        is HealthConnectAvailability.Unavailable -> reason
    }
}

private fun PermissionState.displayText(): String {
    return when (this) {
        PermissionState.Unknown -> "unknown"
        is PermissionState.Granted -> "granted ($grantedCount/$requiredCount)"
        is PermissionState.Missing -> "missing ($grantedCount/$requiredCount): ${missingPermissions.joinToString()}"
    }
}

private fun LocalDateTime.formatDateTime(): String =
    format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

private fun formatDecimal(value: Double): String {
    val roundedOneDecimal = (value * 10.0).roundToInt() / 10.0
    return if (roundedOneDecimal % 1.0 == 0.0) {
        roundedOneDecimal.toInt().toString()
    } else {
        roundedOneDecimal.toString()
    }
}
