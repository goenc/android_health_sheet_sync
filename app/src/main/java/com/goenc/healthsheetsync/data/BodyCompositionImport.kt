package com.goenc.healthsheetsync.data

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

internal fun parseBodyCompositionImport(text: String): List<BodyCompositionMeasurement> {
    val trimmed = text.trim().removePrefix("\uFEFF").trim()
    val jsonText = if (trimmed.startsWith("```")) {
        val lines = trimmed.lines()
        require(lines.size >= 3 && lines.first().trim().equals("```json", true) && lines.last().trim() == "```") {
            "JSONコードフェンスは```json ... ```の形式にしてください"
        }
        lines.drop(1).dropLast(1).joinToString("\n")
    } else trimmed
    val root = try { JSONObject(jsonText) } catch (_: Exception) { error("体組成のJSONオブジェクトを貼り付けてください") }
    require(root.optString("type") == "bodyComposition") { "体組成用の解析結果ではありません（type: bodyCompositionが必要）" }
    val rows = root.optJSONArray("measurements") ?: error("measurements配列がありません")
    require(rows.length() in 1..500) { "体組成の測定は1～500件で取り込んでください" }
    return (0 until rows.length()).map { index ->
        try {
            val row = rows.getJSONObject(index)
            val date = LocalDate.parse(row.requiredBodyString("targetDate"))
            val time = row.optionalBodyString("measuredTime").takeIf { it.isNotBlank() }?.let(LocalTime::parse)
            val values = row.optJSONArray("values") ?: error("values配列がありません")
            validateBodyCompositionMeasurement(BodyCompositionMeasurement(
                targetDate = date, measuredTime = time, deviceModel = row.optionalBodyString("deviceModel"),
                source = row.optionalBodyString("source").ifBlank { "photo" },
                values = (0 until values.length()).map { position -> values.getJSONObject(position).let { value ->
                    BodyCompositionValue(value.requiredBodyString("metricId"), value.requiredBodyString("value"), value.requiredBodyString("unit", allowBlank = true))
                } },
            ))
        } catch (error: Exception) {
            throw IllegalArgumentException("体組成JSONの${index + 1}件目: ${error.message ?: "形式を確認してください"}")
        }
    }
}

private fun JSONObject.requiredBodyString(key: String, allowBlank: Boolean = false): String {
    val value = opt(key)
    require(value is String && (allowBlank || value.isNotBlank())) { "${key}は文字列で指定してください" }
    return value.trim()
}

private fun JSONObject.optionalBodyString(key: String): String =
    if (!has(key) || isNull(key)) "" else requiredBodyString(key, allowBlank = true)
