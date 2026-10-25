package com.goenc.healthsheetsync.data

import java.time.LocalDate
import java.time.LocalTime

internal val BODY_MEASUREMENT_HEADERS = listOf("measurementId", "targetDate", "measuredTime", "deviceModel", "source")
internal val BODY_VALUE_HEADERS = listOf("measurementId", "metricId", "value", "unit")

internal fun bodyMeasurementRows(measurements: List<BodyCompositionMeasurement>): List<List<String>> =
    listOf(BODY_MEASUREMENT_HEADERS) + measurements.map(::validateBodyCompositionMeasurement).map {
        listOf(it.id, it.targetDate.toString(), it.measuredTime?.toString().orEmpty(), it.deviceModel, it.source)
    }

internal fun bodyValueRows(measurements: List<BodyCompositionMeasurement>): List<List<String>> =
    listOf(BODY_VALUE_HEADERS) + measurements.map(::validateBodyCompositionMeasurement).flatMap { measurement ->
        measurement.values.map { listOf(measurement.id, it.metricId, it.value, it.unit) }
    }

/** ヘッダー、参照先、重複を検証してからDB置換に渡す。 */
internal fun parseBodyCompositionTables(
    measurementRows: List<List<String>>,
    valueRows: List<List<String>>,
): List<BodyCompositionMeasurement> {
    fun rows(input: List<List<String>>, headers: List<String>): List<List<String>> {
        if (input.isEmpty()) return emptyList()
        val actual = input.first().map(String::trim)
        require(actual.distinct().size == actual.size && headers.all { it in actual }) { "体組成シートの列名を確認してください" }
        return input.drop(1).filter { row -> row.any(String::isNotBlank) }.map { row ->
            headers.map { header -> row.getOrNull(actual.indexOf(header)).orEmpty().trim() }
        }
    }
    val measurements = rows(measurementRows, BODY_MEASUREMENT_HEADERS)
    val values = rows(valueRows, BODY_VALUE_HEADERS)
    val ids = measurements.map { it[0] }
    require(ids.distinct().size == ids.size) { "体組成の測定IDが重複しています" }
    require(values.all { it[0] in ids }) { "存在しない体組成測定への参照があります" }
    require(values.map { it[0] to it[1] }.distinct().size == values.size) { "体組成の測定値が重複しています" }
    val grouped = values.groupBy { it[0] }
    return measurements.map { row -> validateBodyCompositionMeasurement(BodyCompositionMeasurement(
        id = row[0], targetDate = LocalDate.parse(row[1]), measuredTime = row[2].takeIf(String::isNotBlank)?.let(LocalTime::parse),
        deviceModel = row[3], source = row[4], values = grouped[row[0]].orEmpty().map { BodyCompositionValue(it[1], it[2], it[3]) },
    )) }
}
