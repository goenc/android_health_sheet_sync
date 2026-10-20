package com.goenc.healthsheetsync.health

import java.time.LocalDate
import kotlin.math.roundToInt

data class DailyEnergyCalculation(
    val pal: Double,
    val estimatedTotalKcal: Int,
)

data class DailyEnergyDisplay(
    val steps: Long,
    val basalMetabolicRate: Int,
    val pal: Double,
    val estimatedTotalKcal: Int,
)

object DailyEnergyCalculator {
    const val PAL_INTERCEPT = 1.238
    const val STEPS_COEFFICIENT = 0.00002053
    const val DEFAULT_BASAL_METABOLIC_RATE = 1_371

    fun calculate(steps: Long, basalMetabolicRate: Int): DailyEnergyCalculation {
        require(steps >= 0) { "歩数は0以上で指定してください" }
        require(basalMetabolicRate > 0) { "基礎代謝量は0より大きい整数で指定してください" }

        val pal = PAL_INTERCEPT + STEPS_COEFFICIENT * steps.toDouble()
        return DailyEnergyCalculation(
            pal = pal,
            estimatedTotalKcal = (basalMetabolicRate * pal).roundToInt(),
        )
    }

    fun resolveDisplay(
        targetDate: LocalDate,
        today: LocalDate,
        currentSteps: Long?,
        currentBasalMetabolicRate: Int,
        snapshot: DailyEnergySnapshot?,
        manualRecords: List<ManualHealthRecord> = emptyList(),
    ): DailyEnergyDisplay? {
        if (targetDate.isAfter(today)) return null
        if (targetDate.isBefore(today)) {
            return snapshot
                ?.takeIf { it.targetDate == targetDate }
                ?.let { applyBasalMetabolicRateHistory(it, manualRecords) }
                ?.let {
                    DailyEnergyDisplay(
                        steps = it.steps,
                        basalMetabolicRate = it.basalMetabolicRate,
                        pal = it.pal,
                        estimatedTotalKcal = it.estimatedTotalKcal,
                    )
                }
        }
        return currentSteps?.let { steps ->
            val effectiveBasalMetabolicRate = BasalMetabolicRateHistory.resolve(targetDate, currentBasalMetabolicRate, manualRecords)
            val calculation = calculate(steps, effectiveBasalMetabolicRate)
            DailyEnergyDisplay(
                steps = steps,
                basalMetabolicRate = effectiveBasalMetabolicRate,
                pal = calculation.pal,
                estimatedTotalKcal = calculation.estimatedTotalKcal,
            )
        }
    }

    /** Keep the stored steps and original snapshot intact, so removing history restores the original value. */
    fun applyBasalMetabolicRateHistory(snapshot: DailyEnergySnapshot, records: List<ManualHealthRecord>): DailyEnergySnapshot {
        val value = BasalMetabolicRateHistory.latestOnOrBefore(snapshot.targetDate, records) ?: return snapshot
        if (value == snapshot.basalMetabolicRate) return snapshot
        val calculation = calculate(snapshot.steps, value)
        return snapshot.copy(basalMetabolicRate = value, pal = calculation.pal, estimatedTotalKcal = calculation.estimatedTotalKcal)
    }
}
