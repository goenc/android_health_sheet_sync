package com.goenc.healthsheetsync.health

/**
 * Calculates the fat-free mass index from body weight, height, and body-fat percentage.
 */
internal fun ffmi(
    weightKg: Double,
    heightCm: Double,
    bodyFatPercent: Double,
): Double? {
    if (!weightKg.isFinite() || weightKg <= 0.0 ||
        !heightCm.isFinite() || heightCm <= 0.0 ||
        !bodyFatPercent.isFinite() || bodyFatPercent < 0.0 || bodyFatPercent >= 100.0
    ) {
        return null
    }

    val heightM = heightCm / 100.0
    val fatFreeMassKg = weightKg * (1.0 - bodyFatPercent / 100.0)
    return (fatFreeMassKg / (heightM * heightM))
        .takeIf { it.isFinite() && it > 0.0 }
}
