package com.goenc.healthsheetsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.ffmi
import com.goenc.healthsheetsync.health.navyMaleBodyFatPercent
import java.time.LocalDate

@Composable
internal fun SelectedDaySummary(
    day: ChartDaySelection?,
    graphValues: SelectedGraphValues?,
    manualRecords: List<ManualHealthRecord>,
    dailyBodySettings: List<DailyBodySetting>,
    onAddManualRecord: () -> Unit,
) {
    val rightColumnIndent = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.fontSize.toDp() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PopupBackground, RoundedCornerShape(8.dp))
            .padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryInfoLine(day?.let { it.date.formatMonthDayWithWeekday() } ?: "日付 -", ChartBlue, true,
                    modifier = Modifier.weight(1f))
                SummaryInfoLine(
                    text = "推定総消費 ${day?.dailyEnergy?.estimatedTotalKcal?.let(::formatIntegerWithGrouping) ?: "-"} kcal",
                    color = ChartSummary,
                    bold = true,
                    modifier = Modifier.weight(1.6f).padding(start = rightColumnIndent),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(0.8f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryInfoLine(
                    text = "朝 ${day?.morning.weightText() ?: "-"}",
                    color = ChartBlue,
                    bold = true,
                    modifier = Modifier.weight(1f),
                )
                SummaryInfoLine(
                    text = "夜 ${day?.night.weightText() ?: "-"}",
                    color = ChartBlue,
                    bold = true,
                    modifier = Modifier.weight(1f),
                )
                SummaryInfoLine(
                    text = "差 ${day?.weightDifferenceText() ?: "-"}",
                    color = ChartBlue,
                    bold = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryInfoLine(
                    text = "歩数 ${day?.steps?.steps?.let { "${it}歩" } ?: "-"}",
                    modifier = Modifier.weight(1f),
                )
                SummaryInfoLine(
                    text = "距離 ${formatDistanceMeters(day?.steps?.distanceMeters)}",
                    modifier = Modifier.weight(1.6f).padding(start = rightColumnIndent),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryInfoLine(
                    text = "血糖値 ${graphValues?.glucoseText ?: "-"}",
                    modifier = Modifier.weight(1f),
                )
                SummaryInfoLine(
                    text = "A1c ${graphValues?.a1cText ?: "-"}",
                    modifier = Modifier.weight(1.6f).padding(start = rightColumnIndent),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryInfoLine("血圧 ${graphValues?.bloodPressureText ?: "-"}", modifier = Modifier.weight(1f))
                SummaryInfoLine(
                    text = "腹囲 ${graphValues?.waistText ?: "-"} (" +
                        (selectedDayWaistHeightRatioText(day?.date, graphValues?.waistCm, dailyBodySettings) ?: "-") + ")",
                    modifier = Modifier.weight(1.6f).padding(start = rightColumnIndent),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryInfoLine(
                    text = "BMI ${selectedDayBmiText(day, dailyBodySettings) ?: "-"}",
                    modifier = Modifier.weight(1f),
                )
                SummaryInfoLine(
                    text = "FFMI ${selectedDayFfmiText(day, manualRecords, dailyBodySettings) ?: "-"}",
                    modifier = Modifier.weight(1.6f).padding(start = rightColumnIndent),
                )
            }
        }
        FloatingActionButton(
            onClick = onAddManualRecord,
            containerColor = AppPrimary,
            contentColor = Color.White,
            modifier = Modifier.size(46.dp),
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun selectedDayBmiText(
    day: ChartDaySelection?,
    dailyBodySettings: List<DailyBodySetting>,
): String? {
    val morningWeightKg = day?.morning?.weightKg ?: return null
    val heightCm = dailyBodySettings
        .filter { !it.targetDate.isAfter(day.date) }
        .maxWithOrNull(compareBy<DailyBodySetting> { it.targetDate }.thenBy { it.updatedAt })
        ?.heightCm
        ?: return null
    if (!morningWeightKg.isFinite() || morningWeightKg <= 0.0 || !heightCm.isFinite() || heightCm <= 0.0) {
        return null
    }
    val heightM = heightCm / 100.0
    return (morningWeightKg / (heightM * heightM))
        .takeIf { it.isFinite() && it > 0.0 }
        ?.let(::formatDecimal)
}

internal fun selectedDayFfmiText(
    day: ChartDaySelection?,
    manualRecords: List<ManualHealthRecord>,
    dailyBodySettings: List<DailyBodySetting>,
): String? {
    val date = day?.date ?: return null
    val weightKg = day.morning?.weightKg ?: return null
    val waistCm = manualRecords.latestManualMeasurementOnOrBefore(ManualRecordType.Waist, date)
    val neckCm = manualRecords.latestManualMeasurementOnOrBefore(ManualRecordType.Neck, date)
    val heightCm = dailyBodySettings
        .filter { !it.targetDate.isAfter(date) }
        .maxWithOrNull(compareBy<DailyBodySetting> { it.targetDate }.thenBy { it.updatedAt })
        ?.heightCm
    val bodyFat = if (waistCm != null && neckCm != null && heightCm != null) {
        navyMaleBodyFatPercent(
            heightCm = heightCm,
            waistCm = waistCm,
            neckCm = neckCm,
        )
    } else {
        null
    }
    val ffmiValue = if (bodyFat != null && heightCm != null) {
        ffmi(
            weightKg = weightKg,
            heightCm = heightCm,
            bodyFatPercent = bodyFat,
        )
    } else {
        null
    }
    return if (bodyFat != null && ffmiValue != null) {
        formatFfmiValue(ffmiValue, bodyFat)
    } else {
        null
    }
}

private fun List<ManualHealthRecord>.latestManualMeasurementOnOrBefore(
    type: ManualRecordType,
    date: LocalDate,
): Double? {
    return filter {
        it.type == type &&
            it.invalidatedAt == null &&
            !it.measuredAt.toLocalDate().isAfter(date)
    }
        .maxByOrNull { it.measuredAt }
        ?.valueText
        ?.removeSuffix(" cm")
        ?.trim()
        ?.toDoubleOrNull()
}

@Composable
private fun SummaryInfoLine(
    text: String,
    color: Color = AppText,
    bold: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
) {
    val displayText = buildAnnotatedString {
        text.split(ASSUMED_VALUE_SUFFIX).forEachIndexed { index, part ->
            if (index > 0) {
                withStyle(SpanStyle(fontSize = 10.sp)) {
                    append(ASSUMED_VALUE_SUFFIX)
                }
            }
            append(part)
        }
    }
    Text(
        text = displayText,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
    )
}
