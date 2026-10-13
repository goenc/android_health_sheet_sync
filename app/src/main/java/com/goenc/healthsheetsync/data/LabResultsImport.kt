package com.goenc.healthsheetsync.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

private val LAB_RESULT_IMPORT_FIELDS = setOf(
    "targetDate",
    "itemName",
    "value",
)
private val LEGACY_LAB_RESULT_IMPORT_FIELDS = setOf("unit", "referenceRange", "flag")

/** JSONの行単位で利用者へ返せる検査結果取り込みエラー。 */
internal class LabResultsImportException(
    message: String,
    val rowNumber: Int? = null,
    val itemName: String? = null,
) : IllegalArgumentException(message)

/** 保存前に、追加候補・同一結果・競合を分けた純粋な判定結果。 */
internal data class LabResultsImportPlan(
    val additions: List<LabResult>,
    val duplicates: List<LabResult>,
    val conflicts: List<LabResultsImportConflict>,
) {
    val canAppend: Boolean
        get() = conflicts.isEmpty()
}

internal data class LabResultsImportConflict(
    val incoming: LabResult,
    val existing: List<LabResult>,
    val reason: String,
)

/**
 * ChatGPTから返された検査結果JSONを検証して、保存可能な検査結果へ変換する。
 *
 * トップレベルは配列だけを許可する。各要素は日付・項目名・値を持つJSONオブジェクトで、
 * すべての値は文字列でなければならない。値は数値化せず、外側の空白だけを除去する。
 */
internal fun parseLabResultsImport(text: String): List<LabResult> {
    val payload = unwrapJsonCodeFence(text)
    if (payload.isBlank()) {
        throw LabResultsImportException("検査結果JSONが空です")
    }
    if (!payload.startsWith("[") || !payload.endsWith("]")) {
        throw LabResultsImportException("検査結果JSONのトップレベルは配列にしてください")
    }

    val array = runCatching { JSONArray(payload) }.getOrElse { error ->
        throw LabResultsImportException("検査結果JSONを解析できません: ${error.message.orEmpty()}")
    }
    if (array.length() == 0) {
        throw LabResultsImportException("検査結果JSONの配列が空です")
    }

    val records = (0 until array.length()).map { index ->
        val rowNumber = index + 1
        val objectValue = runCatching { array.get(index) }.getOrElse { error ->
            throw importError(rowNumber, null, "JSON要素を読み取れません: ${error.message.orEmpty()}")
        }
        if (objectValue !is JSONObject) {
            throw importError(rowNumber, null, "JSON要素はオブジェクトにしてください")
        }
        parseImportObject(objectValue, rowNumber)
    }

    val duplicate = records
        .withIndex()
        .groupBy { (_, record) -> record.importKey() }
        .values
        .firstOrNull { it.size > 1 }
    if (duplicate != null) {
        val first = duplicate.first()
        val record = first.value
        val rows = duplicate.joinToString(", ") { (index, _) -> (index + 1).toString() }
        throw LabResultsImportException(
            "同じ日付・項目名の検査結果が重複しています（${rows}件目: " +
                "${record.targetDate}, ${record.itemName}）",
            rowNumber = first.index + 1,
            itemName = record.itemName,
        )
    }
    return records
}

/**
 * 取り込み結果とSheetsの最新値を比較する。通信・保存・時刻に依存しない純粋関数。
 * 同日・同項目に既存行が複数ある場合は、内容が一致していても安全のため競合にする。
 */
internal fun planLabResultsImport(
    incoming: List<LabResult>,
    existing: List<LabResult>,
): LabResultsImportPlan {
    val additions = mutableListOf<LabResult>()
    val duplicates = mutableListOf<LabResult>()
    val conflicts = mutableListOf<LabResultsImportConflict>()

    val existingByDateAndItem = existing.groupBy { it.dateAndItemKey() }
    val incomingCounts = incoming.groupingBy { it.importKey() }.eachCount()

    incoming.forEach { record ->
        val sameIncomingKeyCount = incomingCounts[record.importKey()] ?: 0
        if (sameIncomingKeyCount > 1) {
            conflicts += LabResultsImportConflict(
                incoming = record,
                existing = emptyList(),
                reason = "取り込みデータ内で同じ日付・項目名が重複しています",
            )
            return@forEach
        }

        val sameDateAndItem = existingByDateAndItem[record.dateAndItemKey()].orEmpty()
        when {
            sameDateAndItem.isEmpty() -> additions += record
            sameDateAndItem.size > 1 -> conflicts += LabResultsImportConflict(
                incoming = record,
                existing = sameDateAndItem,
                reason = "保存先に同日・同項目の既存行が複数あるため判定できません",
            )
            else -> {
                val current = sameDateAndItem.single()
                when {
                    labResultValuesEqual(current.value, record.value) -> duplicates += record
                    else -> conflicts += LabResultsImportConflict(
                        incoming = record,
                        existing = sameDateAndItem,
                        reason = "同日・同項目の値が保存済みの結果と異なります",
                    )
                }
            }
        }
    }

    return LabResultsImportPlan(
        additions = additions,
        duplicates = duplicates,
        conflicts = conflicts,
    )
}

/**
 * 検査票の読み取りを依頼する日本語プロンプトを作る。
 * 項目名は履歴の表記を揃えるために提示し、患者を特定する情報は要求しない。
 */
internal fun buildLabResultsPrompt(existingItemNames: Collection<String>): String {
    val itemNames = existingItemNames
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
    val itemNameSection = if (itemNames.isEmpty()) {
        "（既存項目名なし）"
    } else {
        itemNames.joinToString(separator = "\n") { "- $it" }
    }

    return """
        検査票から検査結果を読み取り、次の3項目だけを持つJSON配列を返してください。
        回答はjsonコードブロック1個だけにし、中身はJSONのみにしてください。ブロックの外に説明文を付けないでください。
        患者名、カルテ番号、画像の説明、表、見出しは出力しないでください。
        1項目を1行にまとめ、余分な字下げや空行を入れないでください。
        配列は先頭の[から最後の]まで完全に出力し、その後でコードブロックを閉じてください。

        既存項目名（同じ意味だと確実に判断できる検査だけ表記を合わせる。別の検査を統合しない）:
        $itemNameSection

        各要素は次の3キーだけを持ち、すべて文字列にしてください。
        targetDate: 検査日をYYYY-MM-DDで記載
        itemName: 検査項目名
        value: 検査票の値を原文のまま記載
        単位、基準範囲、高低表示（unit、referenceRange、flag）はJSONに含めないでください。

        定性値、(-)などの記号、不等号付きの値、小数点以下の末尾0を含む値は文字列のまま転記してください。
        読み取れる全項目を抽出してください。読めない値または検査日が不明な項目は、推測せずJSON配列から除外してください。
        未読項目はitemNameごとに把握し、値を作らないでください。同じtargetDate・itemNameの行を二つ以上出力しないでください。
        医学的な判定を追加しないでください。
        コードブロックの中にJSON配列以外の文字列を入れないでください。項目を省略したり、途中を「...」で置き換えたりしないでください。
    """.trimIndent()
}

private fun parseImportObject(json: JSONObject, rowNumber: Int): LabResult {
    val keys = json.keys().asSequence().toSet()
    val missing = LAB_RESULT_IMPORT_FIELDS - keys
    if (missing.isNotEmpty()) {
        throw importError(rowNumber, null, "必須キーがありません: ${missing.sorted().joinToString(", ")}")
    }
    val extra = keys - LAB_RESULT_IMPORT_FIELDS - LEGACY_LAB_RESULT_IMPORT_FIELDS
    if (extra.isNotEmpty()) {
        throw importError(rowNumber, null, "未対応のキーがあります: ${extra.sorted().joinToString(", ")}")
    }

    val fields = LAB_RESULT_IMPORT_FIELDS.associateWith { key ->
        val value = runCatching { json.get(key) }.getOrElse { error ->
            throw importError(rowNumber, null, "キー $key を読み取れません: ${error.message.orEmpty()}")
        }
        if (value !is String) {
            throw importError(rowNumber, null, "キー $key は文字列にしてください")
        }
        value.trim()
    }

    val targetDateText = fields.getValue("targetDate")
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(targetDateText)) {
        throw importError(rowNumber, fields.getValue("itemName"), "日付はYYYY-MM-DDで指定してください")
    }
    val targetDate = runCatching { LocalDate.parse(targetDateText) }.getOrNull()
        ?: throw importError(rowNumber, fields.getValue("itemName"), "日付が不正です: $targetDateText")

    val itemName = fields.getValue("itemName")
    if (itemName.isBlank()) {
        throw importError(rowNumber, null, "itemNameは必須です")
    }
    val value = fields.getValue("value")
    if (value.isBlank()) {
        throw importError(rowNumber, itemName, "valueは必須です。読めない値を推測して補わないでください")
    }

    return LabResult(
        targetDate = targetDate,
        itemName = itemName,
        value = value,
    )
}

private fun unwrapJsonCodeFence(text: String): String {
    val trimmed = text.trim().removePrefix("\uFEFF").trim()
    if (!trimmed.startsWith("```")) return trimmed

    val lines = trimmed.lines()
    val opening = lines.firstOrNull()?.trim()
    val closing = lines.lastOrNull()?.trim()
    if (lines.size < 3 || opening?.equals("```json", ignoreCase = true) != true || closing != "```") {
        throw LabResultsImportException("JSONコードフェンスは```json ... ```の形式にしてください")
    }
    return lines.drop(1).dropLast(1).joinToString("\n").trim()
}

private fun importError(rowNumber: Int, itemName: String?, detail: String): LabResultsImportException {
    val item = itemName?.takeIf(String::isNotBlank)?.let { "（項目: $it）" }.orEmpty()
    return LabResultsImportException("検査結果JSONの${rowNumber}件目$item: $detail", rowNumber, itemName)
}

private fun LabResult.importKey(): LabResultImportKey = LabResultImportKey(targetDate, itemName)

private fun LabResult.dateAndItemKey(): LabResultDateAndItemKey = LabResultDateAndItemKey(targetDate, itemName)

private data class LabResultImportKey(
    val targetDate: LocalDate,
    val itemName: String,
)

private data class LabResultDateAndItemKey(
    val targetDate: LocalDate,
    val itemName: String,
)
