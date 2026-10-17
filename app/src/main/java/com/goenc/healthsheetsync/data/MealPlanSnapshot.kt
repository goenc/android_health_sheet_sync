package com.goenc.healthsheetsync.data

import java.math.BigDecimal
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class CurrentMealPlan(
    val name: String,
    val sections: List<MealPlanSection>,
)

data class MealPlanSection(
    val title: String,
    val lines: List<String>,
)

/**
 * Rebuilds the first active PfcPlanBoard plan from immutable Drive sync batches.
 * Newer revisions replace older rows; deletes remain as tombstones so stale
 * upserts cannot bring deleted rows back.
 */
fun parseCurrentMealBatches(batches: List<JSONObject>): CurrentMealPlan? {
    val rows = linkedMapOf<RowKey, SnapshotRow>()
    batches.forEach { batch -> applyBatch(batch, rows) }

    val planRow = rows.values
        .asSequence()
        .filter { it.entityType == SnapshotEntity.PLAN && it.payload != null }
        .filterNot { it.payload?.isArchived() == true }
        .minWithOrNull(
            compareBy<SnapshotRow> { it.payload?.intValue("displayOrder", 0) ?: 0 }
                .thenBy { it.payload?.longValue("createdUtcTicks", 0L) ?: 0L }
                .thenBy { it.payload?.stringOrNull("name").orEmpty() }
                .thenBy { it.payload?.stringOrNull("id") ?: it.rowKey },
        )
        ?: return null

    val plan = planRow.payload ?: return null
    val planId = plan.stringOrNull("id") ?: planRow.rowKey
    val sections = mutableListOf<MealPlanSection>()

    plan.stringOrNull("memo")?.let { memo ->
        sections += MealPlanSection("プランメモ", listOf("メモ: $memo"))
    }

    val activeRows = rows.values.filter { it.payload != null }
    val foodSnapshots = activeRows
        .filter { it.entityType == SnapshotEntity.FOOD }
        .filterNot { it.payload?.isArchived() == true }
        .associateBy { row ->
            val payload = row.payload
            normalizeId(payload?.stringOrNull("dailyPlanMealItemSnapshotId") ?: row.rowKey)
        }
    val recipeSnapshots = activeRows
        .filter { it.entityType == SnapshotEntity.RECIPE }
        .filterNot { it.payload?.isArchived() == true }
        .associateBy { row ->
            val payload = row.payload
            normalizeId(payload?.stringOrNull("dailyPlanMealItemSnapshotId") ?: row.rowKey)
        }

    val planMeals = activeRows
        .filter { it.entityType == SnapshotEntity.MEAL }
        .filterNot { it.payload?.isArchived() == true }
        .filter { row ->
            normalizeId(row.payload?.stringOrNull("dailyPlanId")) == normalizeId(planId)
        }

    for (slot in MEAL_SLOT_NAMES.indices) {
        val mealRow = planMeals
            .asSequence()
            .filter { it.payload?.intValue("slot", -1) == slot }
            .maxByOrNull { it.revision }
            ?: continue
        val meal = mealRow.payload ?: continue
        val mealId = meal.stringOrNull("id") ?: mealRow.rowKey
        val lines = mutableListOf<String>()

        meal.stringOrNull("name")?.let { lines += "料理: $it" }
        meal.stringOrNull("memo")?.let { lines += "メモ: $it" }

        val mealItems = activeRows
            .asSequence()
            .filter { it.entityType == SnapshotEntity.ITEM }
            .filterNot { it.payload?.isArchived() == true }
            .filter { row -> row.payload?.booleanValue("isEnabled", true) != false }
            .filter { row ->
                normalizeId(row.payload?.stringOrNull("dailyPlanMealSnapshotId")) == normalizeId(mealId)
            }
            .sortedWith(
                compareBy<SnapshotRow> { it.payload?.intValue("displayOrder", 0) ?: 0 }
                    .thenBy { it.rowKey },
            )

        mealItems.forEach { itemRow ->
            val item = itemRow.payload ?: return@forEach
            val itemId = item.stringOrNull("id") ?: itemRow.rowKey
            val isRecipe = item.intValue("componentType", 0) == RECIPE_COMPONENT_TYPE
            val child = if (isRecipe) {
                recipeSnapshots[normalizeId(itemId)]?.payload
            } else {
                foodSnapshots[normalizeId(itemId)]?.payload
            } ?: return@forEach
            val itemName = child.stringOrNull("name")
                ?: child.stringOrNull("foodName")
                ?: item.stringOrNull("name")
                ?: return@forEach
            val amount = formatStoredAmount(
                amount = item.longValue("standardAmount", 0L),
                unit = item.intValue("standardUnit", -1),
                customUnitName = item.stringOrNull("standardCustomUnitName"),
            )
            lines += listOf(itemName, amount)
                .filter { it.isNotBlank() }
                .joinToString(" ")

            if (isRecipe) {
                child.stringOrNull("memo")?.let { lines += "メモ: $it" }
            }
        }

        if (lines.isNotEmpty()) {
            sections += MealPlanSection(MEAL_SLOT_NAMES[slot], lines)
        }
    }

    return CurrentMealPlan(
        name = plan.stringOrNull("name").orEmpty().ifBlank { "名称未設定のプラン" },
        sections = sections,
    )
}

private fun applyBatch(
    batch: JSONObject,
    rows: MutableMap<RowKey, SnapshotRow>,
) {
    val datasetId = batch.stringOrNull("datasetId")
    if (!datasetId.isNullOrBlank() && !datasetId.equals(MEAL_DATASET_ID, ignoreCase = true)) return

    val changes = batch.arrayValue("changes") ?: return
    for (index in 0 until changes.length()) {
        val change = changes.optJSONObject(index) ?: continue
        val entityType = change.stringOrNull("entityType")
            ?.let(::snapshotEntity)
            ?: continue
        val rowKey = change.stringOrNull("rowKey") ?: continue
        val revision = parseRevision(change.objectValue("revision")) ?: continue
        val kind = change.changeKind() ?: continue
        val key = RowKey(entityType, normalizeId(rowKey))
        val current = rows[key]
        if (current != null && revision <= current.revision) continue

        when (kind) {
            ChangeKind.UPSERT -> {
                val payload = parsePayload(change.valueIgnoreCase("payloadJson")) ?: continue
                rows[key] = SnapshotRow(entityType, rowKey, payload, revision)
            }
            ChangeKind.DELETE -> {
                rows[key] = SnapshotRow(entityType, rowKey, null, revision)
            }
        }
    }
}

private fun parsePayload(value: Any?): JSONObject? = when (value) {
    is JSONObject -> value
    is String -> value.takeIf { it.isNotBlank() }?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }
    else -> null
}

private fun parseRevision(value: JSONObject?): SnapshotRevision? {
    if (value == null) return null
    val operationId = value.stringOrNull("operationId") ?: return null
    return SnapshotRevision(
        editedUtcTicks = value.longValue("editedUtcTicks", Long.MIN_VALUE),
        deviceId = value.stringOrNull("deviceId").orEmpty(),
        deviceSequence = value.longValue("deviceSequence", Long.MIN_VALUE),
        operationId = operationId,
    )
}

private fun JSONObject.changeKind(): ChangeKind? = when (val value = valueIgnoreCase("changeKind")) {
    is Number -> when (value.toInt()) {
        0 -> ChangeKind.UPSERT
        1 -> ChangeKind.DELETE
        else -> null
    }
    is String -> when (value.trim().lowercase(Locale.ROOT)) {
        "0", "upsert" -> ChangeKind.UPSERT
        "1", "delete" -> ChangeKind.DELETE
        else -> null
    }
    else -> null
}

private fun snapshotEntity(value: String): SnapshotEntity? =
    value.substringAfterLast('.').lowercase(Locale.ROOT).let { type ->
        when (type) {
            "dailyplanentity", "dailyplans" -> SnapshotEntity.PLAN
            "dailyplanmealsnapshotentity", "dailyplanmealsnapshots" -> SnapshotEntity.MEAL
            "dailyplanmealitemsnapshotentity", "dailyplanmealitemsnapshots" -> SnapshotEntity.ITEM
            "dailyplanfoodsnapshotentity", "dailyplanfoodsnapshots" -> SnapshotEntity.FOOD
            "dailyplanrecipesnapshotentity", "dailyplanrecipesnapshots" -> SnapshotEntity.RECIPE
            else -> null
        }
    }

private fun formatStoredAmount(amount: Long, unit: Int, customUnitName: String?): String {
    if (unit < 0) return ""
    val scale = if (unit == GRAM_UNIT || unit == MILLILITER_UNIT) MASS_SCALE else COUNT_SCALE
    val value = BigDecimal.valueOf(amount)
        .divide(BigDecimal.valueOf(scale))
        .stripTrailingZeros()
        .toPlainString()
    val unitName = when (unit) {
        GRAM_UNIT -> "g"
        MILLILITER_UNIT -> "ml"
        2 -> "個"
        3 -> "パック"
        4 -> "切れ"
        5 -> "カップ"
        6 -> "本"
        7 -> "枚"
        CUSTOM_UNIT -> customUnitName.orEmpty()
        else -> ""
    }
    return if (unitName.isBlank()) value else "$value $unitName"
}

private fun normalizeId(value: String?): String = value.orEmpty().lowercase(Locale.ROOT)

private fun JSONObject.valueIgnoreCase(key: String): Any? {
    val iterator = keys()
    while (iterator.hasNext()) {
        val actualKey = iterator.next()
        if (actualKey.equals(key, ignoreCase = true)) {
            return opt(actualKey).takeUnless { it == JSONObject.NULL }
        }
    }
    return null
}

private fun JSONObject.stringOrNull(key: String): String? =
    valueIgnoreCase(key)?.toString()?.takeIf(String::isNotBlank)

private fun JSONObject.arrayValue(key: String): JSONArray? = valueIgnoreCase(key) as? JSONArray

private fun JSONObject.objectValue(key: String): JSONObject? = valueIgnoreCase(key) as? JSONObject

private fun JSONObject.longValue(key: String, default: Long): Long = when (val value = valueIgnoreCase(key)) {
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull() ?: default
    else -> default
}

private fun JSONObject.intValue(key: String, default: Int): Int = when (val value = valueIgnoreCase(key)) {
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull() ?: default
    else -> default
}

private fun JSONObject.booleanValue(key: String, default: Boolean): Boolean = when (val value = valueIgnoreCase(key)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> when (value.trim().lowercase(Locale.ROOT)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> default
    }
    else -> default
}

private fun JSONObject.isArchived(): Boolean = booleanValue("isArchived", false)

private enum class ChangeKind {
    UPSERT,
    DELETE,
}

private enum class SnapshotEntity {
    PLAN,
    MEAL,
    ITEM,
    FOOD,
    RECIPE,
}

private data class RowKey(val entityType: SnapshotEntity, val rowKey: String)

private data class SnapshotRow(
    val entityType: SnapshotEntity,
    val rowKey: String,
    val payload: JSONObject?,
    val revision: SnapshotRevision,
)

private data class SnapshotRevision(
    val editedUtcTicks: Long,
    val deviceId: String,
    val deviceSequence: Long,
    val operationId: String,
) : Comparable<SnapshotRevision> {
    override fun compareTo(other: SnapshotRevision): Int =
        compareValuesBy(
            this,
            other,
            SnapshotRevision::editedUtcTicks,
            SnapshotRevision::deviceId,
            SnapshotRevision::deviceSequence,
            SnapshotRevision::operationId,
        )
}

private val MEAL_SLOT_NAMES = listOf("朝食", "朝間食", "昼食", "夕食", "昼間食", "フリー間食")

private const val RECIPE_COMPONENT_TYPE = 1
private const val GRAM_UNIT = 0
private const val MILLILITER_UNIT = 1
private const val CUSTOM_UNIT = 8
private const val MASS_SCALE = 1_000L
private const val COUNT_SCALE = 1_000_000L
