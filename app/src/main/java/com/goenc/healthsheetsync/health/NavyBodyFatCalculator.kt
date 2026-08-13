package com.goenc.healthsheetsync.health

import kotlin.math.log10

private const val CENTIMETERS_PER_INCH = 2.54
internal const val NAVY_MALE_BODY_FAT_ADJUSTMENT_PERCENT = 3.0

/**
 * Estimates male body-fat percentage with the Hodgdon-Beckett Navy circumference equation.
 * Inputs are centimeters; the equation itself uses inches.
 */
internal fun navyMaleBodyFatPercent(
    heightCm: Double,
    waistCm: Double,
    neckCm: Double,
    adjustmentPercent: Double = NAVY_MALE_BODY_FAT_ADJUSTMENT_PERCENT,
): Double? {
    if (!heightCm.isFinite() || heightCm <= 0.0 ||
        !waistCm.isFinite() || waistCm <= 0.0 ||
        !neckCm.isFinite() || neckCm <= 0.0 ||
        !adjustmentPercent.isFinite()
    ) {
        return null
    }

    val circumferenceValueInches = (waistCm - neckCm) / CENTIMETERS_PER_INCH
    val heightInches = heightCm / CENTIMETERS_PER_INCH
    if (circumferenceValueInches <= 0.0 || heightInches <= 0.0) {
        return null
    }

    return (
        86.010 * log10(circumferenceValueInches) -
            70.041 * log10(heightInches) +
            36.76 +
            adjustmentPercent
        ).takeIf(Double::isFinite)
}
