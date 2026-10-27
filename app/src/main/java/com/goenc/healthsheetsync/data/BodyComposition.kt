package com.goenc.healthsheetsync.data

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

internal data class BodyCompositionMetric(
    val id: String,
    val name: String,
    val unit: String,
    val defaultVisible: Boolean = true,
)

internal val BODY_COMPOSITION_METRICS = listOf(
    BodyCompositionMetric("weight", "体重", "kg"),
    BodyCompositionMetric("clothingWeight", "着衣量", "kg"),
    BodyCompositionMetric("bodyFatPercent", "体脂肪率", "%"),
    BodyCompositionMetric("fatMass", "脂肪量", "kg"),
    BodyCompositionMetric("fatFreeMass", "除脂肪量", "kg"),
    BodyCompositionMetric("muscleMass", "筋肉量", "kg"),
    BodyCompositionMetric("bodyWaterMass", "体水分量", "kg"),
    BodyCompositionMetric("bodyWaterPercent", "体水分率", "%"),
    BodyCompositionMetric("boneMass", "推定骨量", "kg"),
    BodyCompositionMetric("basalMetabolicRate", "基礎代謝量", "kcal/日"),
    BodyCompositionMetric("bodyAge", "体内年齢", "才"),
    BodyCompositionMetric("visceralFatLevel", "内臓脂肪レベル", ""),
    BodyCompositionMetric("legScore", "脚点", "点"),
    BodyCompositionMetric("bmi", "BMI", "", false),
    BodyCompositionMetric("standardWeight", "標準体重", "kg", false),
    BodyCompositionMetric("obesityPercent", "肥満度", "%", false),
)

data class BodyCompositionValue(val metricId: String, val value: String, val unit: String)

/** 日時不明を補完せず、用紙に記載されたローカル測定日時を保持する。 */
data class BodyCompositionMeasurement(
    val id: String = UUID.randomUUID().toString(),
    val targetDate: LocalDate,
    val measuredTime: LocalTime? = null,
    val deviceModel: String = "",
    val source: String = "photo",
    val values: List<BodyCompositionValue>,
)

private val excludedBodyMetrics = setOf("height", "heightCm", "身長")

internal fun validateBodyCompositionMeasurement(measurement: BodyCompositionMeasurement): BodyCompositionMeasurement {
    require(runCatching { UUID.fromString(measurement.id) }.isSuccess) { "体組成の測定IDが不正です" }
    require(measurement.deviceModel.trim().length <= 200) { "機器名は200文字以内にしてください" }
    require(measurement.source in setOf("photo", "manual", "csv")) { "体組成の取り込み元はphoto/manual/csvにしてください" }
    val values = measurement.values.filter { it.metricId.trim() !in excludedBodyMetrics }.map { input ->
        val metricId = if (input.metricId.trim() == "weightKg") "weight" else input.metricId.trim()
        val metric = BODY_COMPOSITION_METRICS.firstOrNull { it.id == metricId || it.name == metricId }
        require(metric != null) { "未対応の体組成項目です: ${input.metricId.take(60)}" }
        val text = input.value.trim()
        require(text.length <= 32 && text.matches(Regex("[+-]?\\d+(\\.\\d+)?"))) { "${metric.name}は数値だけを入力してください" }
        val number = text.toBigDecimal()
        require(number.toDouble().isFinite()) { "${metric.name}の値が大きすぎます" }
        if (metric.id != "obesityPercent") require(number >= BigDecimal.ZERO) { "${metric.name}は負の値にできません" }
        if (metric.id in setOf("bodyFatPercent", "bodyWaterPercent")) require(number <= BigDecimal(100)) { "${metric.name}は100%以下にしてください" }
        if (metric.id in setOf("weight", "basalMetabolicRate", "bmi", "standardWeight")) require(number > BigDecimal.ZERO) { "${metric.name}は0より大きい値にしてください" }
        if (metric.id in setOf("bodyAge", "visceralFatLevel", "legScore")) require(number.stripTrailingZeros().scale() <= 0) { "${metric.name}は整数にしてください" }
        val unit = when (input.unit.trim()) {
            "％" -> "%"
            "歳" -> "才"
            "kcal", "kcal/day" -> "kcal/日"
            "レベル" -> if (metric.id == "visceralFatLevel") "" else "レベル"
            else -> input.unit.trim()
        }
        require(unit == metric.unit) { "${metric.name}の単位は「${metric.unit.ifBlank { "単位なし" }}」です" }
        BodyCompositionValue(metric.id, text, unit)
    }
    require(values.isNotEmpty()) { "体組成項目がありません" }
    require(values.map { it.metricId }.distinct().size == values.size) { "同じ測定に体組成項目が重複しています" }
    return measurement.copy(deviceModel = measurement.deviceModel.trim(), values = values.sortedBy { value ->
        BODY_COMPOSITION_METRICS.indexOfFirst { it.id == value.metricId }
    })
}

internal data class BodyCompositionImportConflict(val incoming: BodyCompositionMeasurement, val reason: String)
internal data class BodyCompositionImportPlan(
    val additions: List<BodyCompositionMeasurement>,
    val updates: List<BodyCompositionMeasurement>,
    val duplicates: List<BodyCompositionMeasurement>,
    val conflicts: List<BodyCompositionImportConflict>,
)

/** 再解析でIDが変わっても重複を防ぎ、曖昧な同日測定は勝手に統合しない。 */
internal fun planBodyCompositionImport(
    incoming: List<BodyCompositionMeasurement>,
    existing: List<BodyCompositionMeasurement>,
): BodyCompositionImportPlan {
    val accepted = existing.map(::validateBodyCompositionMeasurement).toMutableList()
    val additions = mutableListOf<BodyCompositionMeasurement>()
    val updates = mutableListOf<BodyCompositionMeasurement>()
    val duplicates = mutableListOf<BodyCompositionMeasurement>()
    val conflicts = mutableListOf<BodyCompositionImportConflict>()
    incoming.map(::validateBodyCompositionMeasurement).forEach { record ->
        val candidates = accepted.filter { current -> current.id == record.id ||
            (current.targetDate == record.targetDate &&
                (current.deviceModel == record.deviceModel || current.deviceModel.isBlank() || record.deviceModel.isBlank()) &&
                (current.measuredTime == record.measuredTime || current.measuredTime == null || record.measuredTime == null)) }
        val exact = candidates.singleOrNull()?.let { current ->
            current.targetDate == record.targetDate && current.measuredTime == record.measuredTime &&
                current.deviceModel == record.deviceModel && sameBodyCompositionValues(current.values, record.values)
        } == true
        val current = candidates.singleOrNull()
        val missing = current?.let { existingRecord -> record.values.filter { value ->
            existingRecord.values.none { it.metricId == value.metricId }
        } }.orEmpty()
        val shared = current?.let { existingRecord -> record.values.filter { value ->
            existingRecord.values.any { it.metricId == value.metricId }
        } }.orEmpty()
        val canComplete = current != null && record.targetDate == current.targetDate &&
            record.measuredTime != null && record.measuredTime == current.measuredTime &&
            record.deviceModel.isNotBlank() && record.deviceModel == current.deviceModel &&
            shared.isNotEmpty() && sameBodyCompositionValues(shared, current.values.filter { value ->
                shared.any { it.metricId == value.metricId }
            }) && missing.isNotEmpty() && missing.all { it.metricId in setOf("weight", "clothingWeight") }
        when {
            exact -> duplicates += record
            canComplete -> {
                val previous = requireNotNull(current)
                val updated = validateBodyCompositionMeasurement(previous.copy(values = previous.values + missing))
                accepted[accepted.indexOf(previous)] = updated
                val addedIndex = additions.indexOfFirst { it.id == previous.id }
                if (addedIndex >= 0) additions[addedIndex] = updated else {
                    val updateIndex = updates.indexOfFirst { it.id == previous.id }
                    if (updateIndex >= 0) updates[updateIndex] = updated else updates += updated
                }
            }
            candidates.isNotEmpty() -> conflicts += BodyCompositionImportConflict(record,
                "同じ測定の値の相違、または日時・機器が不明な同日測定があります。日時・機器・値を確認するか除外してください")
            else -> { additions += record; accepted += record }
        }
    }
    return BodyCompositionImportPlan(additions, updates, duplicates, conflicts)
}

private fun sameBodyCompositionValues(a: List<BodyCompositionValue>, b: List<BodyCompositionValue>): Boolean =
    a.size == b.size && a.all { left -> b.any { right ->
        left.metricId == right.metricId && left.unit == right.unit && left.value.toBigDecimal().compareTo(right.value.toBigDecimal()) == 0
    } }
