package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

/** 検査項目と結果を主キー・外部キーで結ぶ端末内データベース。 */
internal class LabDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context, "lab_results.db", null, 3,
) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE lab_items (
            item_id TEXT PRIMARY KEY NOT NULL,
            item_name TEXT UNIQUE NOT NULL CHECK(length(trim(item_name)) > 0),
            unit TEXT NOT NULL DEFAULT '',
            reference_seed TEXT NOT NULL DEFAULT '',
            display_order INTEGER NOT NULL DEFAULT 0,
            unit_configured INTEGER NOT NULL DEFAULT 0 CHECK(unit_configured IN (0,1))
        )""")
        // 小数の末尾と精度を保持するため、数値は検証済み10進文字列で保存。
        db.execSQL("""CREATE TABLE lab_results (
            target_date TEXT NOT NULL,
            item_id TEXT NOT NULL REFERENCES lab_items(item_id) ON DELETE RESTRICT,
            value TEXT NOT NULL CHECK(length(value) > 0),
            value_qualifier TEXT NOT NULL CHECK(value_qualifier IN ('=','<','<=','>','>=','text')),
            PRIMARY KEY(target_date, item_id)
        )""")
        db.execSQL("""CREATE TABLE lab_reference_ranges (
            item_id TEXT PRIMARY KEY NOT NULL REFERENCES lab_items(item_id) ON DELETE RESTRICT,
            minimum TEXT, maximum TEXT,
            minimum_inclusive INTEGER NOT NULL CHECK(minimum_inclusive IN (0,1)),
            maximum_inclusive INTEGER NOT NULL CHECK(maximum_inclusive IN (0,1)),
            disabled INTEGER NOT NULL CHECK(disabled IN (0,1)),
            CHECK(disabled = 1 OR minimum IS NOT NULL OR maximum IS NOT NULL)
        )""")
        db.execSQL("CREATE TABLE lab_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        createBodyCompositionTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..2 && newVersion == 3) { "検査データベースの未対応バージョン: $oldVersion → $newVersion" }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE lab_items ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE lab_items SET display_order=rowid")
        }
        createBodyCompositionTables(db)
    }

    @Synchronized fun load(): LabResultsUiState {
        val tables = tables()
        val pending = backupRevision() > backedUpRevision()
        return LabResultsUiState(tables.results,
            status = if (pending) "端末内DBに保存済み。Sheetsへの未反映データあり" else null,
            lastUpdatedAt = metadata("updated_at")?.let(Instant::parse),
            bodyMeasurements = readBodyCompositionMeasurements(readableDatabase))
    }

    @Synchronized fun tables(): LabTables {
        val db = readableDatabase
        val items = itemRows(db)
        val rows = mutableListOf(listOf("targetDate", "itemId", "value", "valueQualifier"))
        db.rawQuery("SELECT r.target_date,r.item_id,r.value,r.value_qualifier FROM lab_results r JOIN lab_items i USING(item_id) ORDER BY r.target_date DESC,i.display_order,i.rowid", null).use { cursor ->
            while (cursor.moveToNext()) rows += (0..3).map { cursor.getString(it) }
        }
        return parseLabRelationalTables(items, rows)
    }

    /** 端末内DBへ先に確定し、同じトランザクションでバックアップ待ちを記録する。 */
    @Synchronized fun appendRecords(incoming: List<LabResult>): LabResultsSaveResult {
        val validated = incoming.map { it.copy(itemName = it.itemName.trim(), value = it.value.trim(), unit = "", referenceRange = "", flag = "") }
        normalizeLabTables(validated)
        val existing = tables()
        val plan = planLabResultsImport(validated, existing.results)
        require(plan.conflicts.isEmpty()) { "端末内DBに値の異なる同日・同項目があります。内容を確認してください" }
        if (plan.additions.isEmpty()) return LabResultsSaveResult(0, plan.duplicates.size)
        val names = existing.items.map { it.itemName }.toSet()
        val newItems = normalizeLabTables(plan.additions).items.filter { it.itemName !in names }
        val items = existing.items + newItems
        val rows = labResultRows(plan.additions, items).drop(1)
        parseLabRelationalTables(labItemRows(items), labResultRows(existing.results + plan.additions, items))
        val db = writableDatabase
        db.beginTransaction()
        try {
            newItems.forEachIndexed { index, item -> upsertItem(db, item, existing.items.size + index) }
            rows.forEach { row -> db.insertOrThrow("lab_results", null, ContentValues().apply {
                put("target_date", row[0]); put("item_id", row[1]); put("value", row[2]); put("value_qualifier", row[3])
            }) }
            putMetadata(db, "updated_at", Instant.now().toString())
            incrementBackupRevision(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return LabResultsSaveResult(plan.additions.size, plan.duplicates.size)
    }

    @Synchronized fun backupSnapshot(): LabBackupSnapshot {
        val rows = mutableListOf(LAB_REFERENCE_BACKUP_HEADERS)
        readableDatabase.rawQuery("SELECT item_id,minimum,maximum,minimum_inclusive,maximum_inclusive,disabled FROM lab_reference_ranges ORDER BY item_id", null).use { cursor ->
            while (cursor.moveToNext()) rows += (0..5).map { cursor.getString(it).orEmpty() }
        }
        return LabBackupSnapshot(tables(), rows, backupRevision(), backedUpRevision(), readBodyCompositionMeasurements(readableDatabase))
    }

    private fun backupRevision() = metadata("backup_revision")?.toLong() ?: 0L
    private fun backedUpRevision() = metadata("backed_up_revision")?.toLong() ?: 0L
    private fun incrementBackupRevision(db: SQLiteDatabase) = putMetadata(db, "backup_revision", (backupRevision() + 1).toString())
    @Synchronized fun markBackedUp(revision: Long) {
        require(revision <= backupRevision()) { "未保存の世代をバックアップ済みにできません" }
        putMetadata(writableDatabase, "backed_up_revision", maxOf(revision, backedUpRevision()).toString())
    }

    @Synchronized fun replace(tables: LabTables, updatedAt: Instant, bodyMeasurements: List<BodyCompositionMeasurement>? = null) {
        // 参照、重複、値形式を検証してから1トランザクションで入れ替える。
        val itemRows = labItemRows(tables.items)
        val rows = labResultRows(tables.results, tables.items)
        parseLabRelationalTables(itemRows, rows)
        bodyMeasurements?.let { parseBodyCompositionTables(bodyMeasurementRows(it), bodyValueRows(it)) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            tables.items.forEachIndexed { index, item -> upsertItem(db, item, index) }
            db.delete("lab_results", null, null)
            rows.drop(1).forEach { row ->
                db.insertOrThrow("lab_results", null, ContentValues().apply {
                    put("target_date", row[0]); put("item_id", row[1]); put("value", row[2]); put("value_qualifier", row[3])
                })
            }
            bodyMeasurements?.let { replaceBodyCompositionMeasurements(db, it) }
            putMetadata(db, "updated_at", updatedAt.toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun appendBodyMeasurements(incoming: List<BodyCompositionMeasurement>): LabResultsSaveResult {
        val plan = planBodyCompositionImport(incoming, readBodyCompositionMeasurements(readableDatabase))
        require(plan.conflicts.isEmpty()) { "既存の体組成測定と相違があります。日時・機器・値を確認してください" }
        if (plan.additions.isEmpty()) return LabResultsSaveResult(0, plan.duplicates.size)
        val db = writableDatabase
        db.beginTransaction()
        try {
            plan.additions.forEach { insertBodyCompositionMeasurement(db, it) }
            putMetadata(db, "updated_at", Instant.now().toString())
            incrementBackupRevision(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return LabResultsSaveResult(plan.additions.size, plan.duplicates.size)
    }

    @Synchronized fun ensureItems(records: List<LabResult>) {
        val db = writableDatabase
        normalizeLabTables(records).items.forEach { upsertItem(db, it) }
    }

    private fun upsertItem(db: SQLiteDatabase, item: LabItem, order: Int? = null) {
        db.rawQuery("SELECT item_name,unit_configured FROM lab_items WHERE item_id=?", arrayOf(item.itemId)).use { cursor ->
            if (cursor.moveToFirst()) {
                require(cursor.getString(0) == item.itemName) { "検査項目IDと項目名の対応が変わっています" }
                val values = ContentValues().apply {
                    if (cursor.getInt(1) == 0) put("unit", item.unit)
                    if (item.initialReferenceRange.isNotBlank()) put("reference_seed", item.initialReferenceRange)
                    order?.let { put("display_order", it) }
                }
                if (values.size() > 0) db.update("lab_items", values, "item_id=?", arrayOf(item.itemId))
            } else db.insertOrThrow("lab_items", null, ContentValues().apply {
                put("item_id", item.itemId); put("item_name", item.itemName); put("unit", item.unit)
                put("reference_seed", item.initialReferenceRange)
                order?.let { put("display_order", it) }
            })
        }
    }

    @Synchronized fun units(): Map<String, String> = readableDatabase.rawQuery(
        "SELECT item_name,unit FROM lab_items", null,
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) } }

    @Synchronized fun references(): Map<String, LabReferenceRange?> = readableDatabase.rawQuery(
        "SELECT i.item_name,r.minimum,r.maximum,r.minimum_inclusive,r.maximum_inclusive,r.disabled FROM lab_reference_ranges r JOIN lab_items i USING(item_id)", null,
    ).use { cursor -> buildMap {
        while (cursor.moveToNext()) put(cursor.getString(0), if (cursor.getInt(5) == 1) null else LabReferenceRange(
            cursor.getString(1)?.toBigDecimal(), cursor.getString(2)?.toBigDecimal(), cursor.getInt(3) == 1, cursor.getInt(4) == 1,
        ))
    } }

    @Synchronized fun saveReference(name: String, range: LabReferenceRange?, unit: String? = null, requestBackup: Boolean = false) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.rawQuery("SELECT item_id FROM lab_items WHERE item_name=?", arrayOf(name)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else labItemId(name).also { upsertItem(db, LabItem(it, name, unit.orEmpty())) }
            }
            db.insertWithOnConflict("lab_reference_ranges", null, ContentValues().apply {
                put("item_id", id); put("minimum", range?.minimum?.toPlainString()); put("maximum", range?.maximum?.toPlainString())
                put("minimum_inclusive", if (range?.minimumInclusive != false) 1 else 0)
                put("maximum_inclusive", if (range?.maximumInclusive != false) 1 else 0)
                put("disabled", if (range == null) 1 else 0)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "基準範囲を保存できませんでした" } }
            unit?.let { db.update("lab_items", ContentValues().apply {
                put("unit", it.trim()); put("unit_configured", 1)
            }, "item_id=?", arrayOf(id)) }
            if (requestBackup) incrementBackupRevision(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun metadata(key: String): String? = readableDatabase.rawQuery(
        "SELECT value FROM lab_metadata WHERE key=?", arrayOf(key),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    @Synchronized fun mark(key: String) = putMetadata(writableDatabase, key, "1")

    private fun putMetadata(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("lab_metadata", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun itemRows(db: SQLiteDatabase): List<List<String>> = buildList {
        add(listOf("itemId", "itemName", "unit", "initialReferenceRange"))
        db.rawQuery("SELECT item_id,item_name,unit,reference_seed FROM lab_items ORDER BY display_order,rowid", null).use { cursor ->
            while (cursor.moveToNext()) add((0..3).map { cursor.getString(it) })
        }
    }

    companion object {
        @Volatile private var instance: LabDatabase? = null
        fun get(context: Context): LabDatabase = instance ?: synchronized(this) {
            instance ?: LabDatabase(context.applicationContext).also { instance = it }
        }
    }
}
