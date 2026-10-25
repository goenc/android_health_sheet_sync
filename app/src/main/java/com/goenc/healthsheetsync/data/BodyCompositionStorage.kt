package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

internal fun createBodyCompositionTables(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE body_measurements (
        measurement_id TEXT PRIMARY KEY NOT NULL,
        target_date TEXT NOT NULL,
        measured_time TEXT,
        device_model TEXT NOT NULL DEFAULT '',
        source TEXT NOT NULL CHECK(source IN ('photo','manual','csv'))
    )""")
    db.execSQL("""CREATE TABLE body_values (
        measurement_id TEXT NOT NULL REFERENCES body_measurements(measurement_id) ON DELETE CASCADE,
        metric_id TEXT NOT NULL,
        value TEXT NOT NULL CHECK(length(value) > 0),
        unit TEXT NOT NULL,
        PRIMARY KEY(measurement_id, metric_id)
    )""")
}

internal fun readBodyCompositionMeasurements(db: SQLiteDatabase): List<BodyCompositionMeasurement> {
    val measurements = mutableListOf(BODY_MEASUREMENT_HEADERS)
    db.rawQuery("SELECT measurement_id,target_date,measured_time,device_model,source FROM body_measurements ORDER BY target_date DESC,measured_time DESC,measurement_id", null).use { cursor ->
        while (cursor.moveToNext()) measurements += (0..4).map { cursor.getString(it).orEmpty() }
    }
    val values = mutableListOf(BODY_VALUE_HEADERS)
    db.rawQuery("SELECT measurement_id,metric_id,value,unit FROM body_values ORDER BY measurement_id,metric_id", null).use { cursor ->
        while (cursor.moveToNext()) values += (0..3).map { cursor.getString(it) }
    }
    return parseBodyCompositionTables(measurements, values)
}

internal fun insertBodyCompositionMeasurement(db: SQLiteDatabase, measurement: BodyCompositionMeasurement) {
    val validated = validateBodyCompositionMeasurement(measurement)
    db.insertOrThrow("body_measurements", null, ContentValues().apply {
        put("measurement_id", validated.id); put("target_date", validated.targetDate.toString())
        put("measured_time", validated.measuredTime?.toString()); put("device_model", validated.deviceModel); put("source", validated.source)
    })
    validated.values.forEach { value -> db.insertOrThrow("body_values", null, ContentValues().apply {
        put("measurement_id", validated.id); put("metric_id", value.metricId); put("value", value.value); put("unit", value.unit)
    }) }
}

/** 呼び出し元の検査結果と同じトランザクション内で置換する。 */
internal fun replaceBodyCompositionMeasurements(db: SQLiteDatabase, measurements: List<BodyCompositionMeasurement>) {
    db.delete("body_values", null, null)
    db.delete("body_measurements", null, null)
    measurements.forEach { insertBodyCompositionMeasurement(db, it) }
}
