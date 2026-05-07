package com.goenc.healthsheetsync.data

/**
 * Future local persistence contract. Room is intentionally not introduced in this
 * initial Health Connect read check to keep the implementation small.
 */
object FutureDatabaseSchema {
    val tables = listOf(
        """
        weight_logs:
        id
        health_connect_id
        measured_at
        target_date
        time_band
        weight_kg
        uploaded_at
        created_at
        updated_at
        """.trimIndent(),
        """
        glucose_logs:
        id
        health_connect_id
        measured_at
        target_date
        time_band
        blood_glucose_mg_dl
        meal_relation
        uploaded_at
        created_at
        updated_at
        """.trimIndent(),
        """
        step_daily:
        id
        target_date UNIQUE
        steps
        aggregation_start_at
        aggregation_end_at
        uploaded_at
        created_at
        updated_at
        """.trimIndent(),
        """
        sync_state:
        id
        data_type
        last_read_at
        last_uploaded_at
        updated_at
        """.trimIndent(),
        """
        debug_health_connect_records:
        id
        record_type
        health_connect_id
        measured_at
        value_text
        source_app_name
        source_package_name
        read_at
        raw_summary
        """.trimIndent(),
    )

    val sheets = listOf(
        "weight_logs: 測定日時 | 対象日 | 時間帯 | 体重kg | 更新日時",
        "glucose_logs: 測定日時 | 対象日 | 時間帯 | 血糖値mg/dL | 食前食後タグ | 更新日時",
        "step_daily: 対象日 | 歩数 | 集計開始日時 | 集計終了日時 | 更新日時",
        "debug_health_connect_records: 種類 | 測定日時 | 値 | 対象アプリ | パッケージ名 | 取得日時",
    )
}
