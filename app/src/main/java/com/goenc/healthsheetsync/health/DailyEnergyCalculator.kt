package com.goenc.healthsheetsync.health

import kotlin.math.roundToInt

data class DailyEnergyCalculation(
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
}
