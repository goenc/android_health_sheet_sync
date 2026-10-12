package com.goenc.healthsheetsync.data

import android.content.Context

internal enum class ChartMetric(val label: String) {
    Weight("体重（kg）"), Steps("歩数（歩）"), Glucose("血糖値（mg/dL）"),
    A1c("HbA1c（%）"), Waist("腹囲（cm）"), BloodPressure("血圧・上下共通（mmHg）"),
}

internal data class ChartAxisRange(val minimum: Double, val maximum: Double) {
    val span: Double get() = maximum - minimum

    fun ratio(value: Double): Double = (value - minimum) / span

    companion object {
        fun parse(minimum: String, maximum: String): ChartAxisRange? {
            val min = minimum.trim().toDoubleOrNull() ?: return null
            val max = maximum.trim().toDoubleOrNull() ?: return null
            return if (min.isFinite() && max.isFinite() && min >= 0 && max > min && (max - min).isFinite()) {
                ChartAxisRange(min, max)
            } else null
        }
    }
}

internal class ChartAxisSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("chart_axis_settings", Context.MODE_PRIVATE)

    fun load(): Map<ChartMetric, ChartAxisRange> = ChartMetric.entries.mapNotNull { metric ->
        ChartAxisRange.parse(preferences.getString("${metric.name}_min", "").orEmpty(),
            preferences.getString("${metric.name}_max", "").orEmpty())?.let { metric to it }
    }.toMap()

    fun save(metric: ChartMetric, range: ChartAxisRange?): Boolean {
        val editor = preferences.edit()
        if (range == null) editor.remove("${metric.name}_min").remove("${metric.name}_max")
        else editor.putString("${metric.name}_min", range.minimum.toString()).putString("${metric.name}_max", range.maximum.toString())
        return editor.commit()
    }
}
