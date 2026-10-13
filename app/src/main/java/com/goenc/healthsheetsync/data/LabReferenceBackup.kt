package com.goenc.healthsheetsync.data

internal val LAB_REFERENCE_BACKUP_HEADERS = listOf("itemId", "minimum", "maximum", "minimumInclusive", "maximumInclusive", "disabled")

/** バックアップの基準範囲を検証し、項目名に解決。保存済み設定の上書きには使わない。 */
internal fun parseLabReferenceBackup(rows: List<List<String>>, items: List<LabItem>): Map<String, LabReferenceRange?> {
    require(rows.firstOrNull() == LAB_REFERENCE_BACKUP_HEADERS) { "labReferenceRangesの列設定を確認してください" }
    val byId = items.associateBy { it.itemId }
    val seen = HashSet<String>()
    return rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.associate { row ->
        val id = row.getOrNull(0).orEmpty()
        val item = requireNotNull(byId[id]) { "基準範囲が存在しない項目IDを参照しています" }
        require(seen.add(id)) { "基準範囲の項目IDが重複しています" }
        fun flag(index: Int): Boolean {
            val value = row.getOrNull(index).orEmpty()
            require(value == "0" || value == "1") { "基準範囲の境界・無効化は0または1にしてください" }
            return value == "1"
        }
        val minimumInclusive = flag(3)
        val maximumInclusive = flag(4)
        val disabled = flag(5)
        item.itemName to if (disabled) null else LabReferenceRange(
            row.getOrNull(1)?.takeIf { it.isNotBlank() }?.toBigDecimal(),
            row.getOrNull(2)?.takeIf { it.isNotBlank() }?.toBigDecimal(), minimumInclusive, maximumInclusive,
        )
    }
}
