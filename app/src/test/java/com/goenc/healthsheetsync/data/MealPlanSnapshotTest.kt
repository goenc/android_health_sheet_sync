package com.goenc.healthsheetsync.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MealPlanSnapshotTest {
    @Test
    fun selectsFirstActivePlanUsingDisplayOrderThenCreationTimeAndStableFallbacks() {
        val batches = listOf(
            batch(
                upsert("DailyPlanEntity", "plan-z", plan("plan-z", "Z", order = 0, created = 20)),
                upsert("DailyPlanEntity", "plan-b", plan("plan-b", "B", order = 0, created = 10)),
                upsert("DailyPlanEntity", "plan-a-z", plan("plan-a-z", "A", order = 0, created = 10)),
                upsert("DailyPlanEntity", "plan-a-a", plan("plan-a-a", "A", order = 0, created = 10)),
                upsert("DailyPlanEntity", "plan-later-order", plan("plan-later-order", "Earlier", order = 1, created = 1)),
                upsert(
                    "DailyPlanEntity",
                    "plan-archived",
                    plan("plan-archived", "Archived", order = 0, created = 0, archived = 1),
                ),
                upsert(
                    "DailyPlanMealSnapshotEntity",
                    "meal-a-a",
                    meal("meal-a-a", "plan-a-a", slot = 0, name = "Selected by ID"),
                ),
                upsert(
                    "DailyPlanMealSnapshotEntity",
                    "meal-a-z",
                    meal("meal-a-z", "plan-a-z", slot = 0, name = "Wrong ID"),
                ),
            ),
        )

        val parsed = parseCurrentMealBatches(batches)

        assertEquals("A", parsed?.name)
        assertEquals("料理: Selected by ID", parsed?.sections?.single()?.lines?.first())
    }

    @Test
    fun buildsSlotSectionsWithOrderedFoodAndRecipeNamesAmountsAndMemos() {
        val planId = "plan-1"
        val breakfastId = "meal-breakfast"
        val lunchId = "meal-lunch"
        val batches = listOf(
            batch(
                upsert(
                    "DailyPlans",
                    planId,
                    plan(planId, "Current plan", order = 0, created = 100, memo = "Plan note"),
                    kind = "upsert",
                ),
                upsert(
                    "PfcPlanBoard.Infrastructure.Persistence.Entities.DailyPlanMealSnapshotEntity",
                    breakfastId,
                    meal(breakfastId, planId, slot = 0, name = "Breakfast set", memo = "Meal note", archived = 0),
                ),
                upsert(
                    "DailyPlanMealSnapshotEntity",
                    lunchId,
                    meal(lunchId, planId, slot = 2, name = "Lunch set"),
                ),
                upsert(
                    "DailyPlanMealItemSnapshotEntity",
                    "item-rice",
                    mealItem("item-rice", breakfastId, componentType = 0, amount = 150_000, unit = 0, order = 0),
                    kind = 0,
                ),
                upsert(
                    "DailyPlanMealItemSnapshots",
                    "item-egg",
                    """{"Id":"item-egg","DailyPlanMealSnapshotId":"$breakfastId","ComponentType":0,"StandardAmount":1250000,"StandardUnit":2,"DisplayOrder":1,"IsEnabled":1}""",
                ),
                upsert(
                    "DailyPlanMealItemSnapshotEntity",
                    "item-soup",
                    mealItem("item-soup", breakfastId, componentType = 1, amount = 250_000, unit = 1, order = 2),
                ),
                upsert(
                    "DailyPlanMealItemSnapshotEntity",
                    "item-disabled",
                    mealItem("item-disabled", breakfastId, componentType = 0, amount = 5_000, unit = 0, order = 3, enabled = 0),
                ),
                upsert(
                    "DailyPlanMealItemSnapshotEntity",
                    "item-yogurt",
                    mealItem("item-yogurt", lunchId, componentType = 0, amount = 500_000, unit = 8, customUnit = "袋", order = 0),
                ),
                upsert("DailyPlanFoodSnapshots", "item-rice", foodSnapshot("米")),
                upsert("DailyPlanFoodSnapshotEntity", "item-egg", foodSnapshot("卵")),
                upsert("DailyPlanRecipeSnapshotEntity", "item-soup", recipeSnapshot("みそ汁", memo = "Recipe note")),
                upsert("DailyPlanFoodSnapshotEntity", "item-disabled", foodSnapshot("Hidden food")),
                upsert("DailyPlanFoodSnapshotEntity", "item-yogurt", foodSnapshot("ヨーグルト")),
                upsert("FoodEntity", "unrelated-food", JSONObject().put("Name", "Ignored global food")),
            ),
        )

        val parsed = parseCurrentMealBatches(batches)

        assertNotNull(parsed)
        assertEquals("Current plan", parsed?.name)
        assertEquals(listOf("プランメモ", "朝食", "昼食"), parsed?.sections?.map { it.title })
        assertEquals(
            listOf(
                "メモ: Plan note",
            ),
            parsed?.sections?.get(0)?.lines,
        )
        assertEquals(
            listOf(
                "料理: Breakfast set",
                "メモ: Meal note",
                "米 150 g",
                "卵 1.25 個",
                "みそ汁 250 ml",
                "メモ: Recipe note",
            ),
            parsed?.sections?.get(1)?.lines,
        )
        assertEquals(
            listOf("料理: Lunch set", "ヨーグルト 0.5 袋"),
            parsed?.sections?.get(2)?.lines,
        )
        assertFalse(parsed?.sections?.flattenLines()?.contains("Hidden food") == true)
    }

    @Test
    fun comparesLongRevisionsAndKeepsFirstPayloadForAnExactRevisionTie() {
        val selectedRevision = revision(
            ticks = "5000000000",
            deviceId = "device-a",
            sequence = 8,
            operationId = "operation-a",
        )
        val batches = listOf(
            batch(
                upsert("DailyPlanEntity", "plan-1", plan("plan-1", "Newest", order = 0, created = 1), selectedRevision),
                upsert(
                    "DailyPlanEntity",
                    "plan-1",
                    plan("plan-1", "Tied second batch must lose", order = 0, created = 1),
                    revision("5000000000", "device-a", 8, "operation-a"),
                ),
            ),
            batch(
                upsert(
                    "DailyPlanEntity",
                    "plan-1",
                    plan("plan-1", "Stale", order = 0, created = 1),
                    revision("4999999999", "device-z", 99, "operation-z"),
                ),
                upsert(
                    "DailyPlanEntity",
                    "plan-1",
                    plan("plan-1", "Higher device sequence", order = 0, created = 1),
                    revision("5000000000", "device-a", 9, "operation-a"),
                ),
            ),
        )

        assertEquals("Higher device sequence", parseCurrentMealBatches(batches)?.name)
    }

    @Test
    fun newerTombstonesWinOverLaterStaleUpsertsAndCanBeSuperseded() {
        val planId = "plan-1"
        val mealId = "meal-1"
        val basePlan = upsert("DailyPlanEntity", planId, plan(planId, "Restored", 0, 1), revision(100))
        val baseMeal = upsert("DailyPlanMealSnapshotEntity", mealId, meal(mealId, planId, 0, "Breakfast"), revision(100))
        val itemPayload = mealItem("item-1", mealId, 0, 100_000, 0, 0)
        val batches = listOf(
            batch(
                basePlan,
                baseMeal,
                upsert("DailyPlanMealItemSnapshotEntity", "item-1", itemPayload, revision(100)),
                upsert("DailyPlanFoodSnapshotEntity", "item-1", foodSnapshot("Rice"), revision(100)),
            ),
            batch(
                delete("DailyPlanMealItemSnapshotEntity", "item-1", revision(200), kind = "delete"),
                upsert("DailyPlanMealItemSnapshotEntity", "item-1", itemPayload, revision(150)),
            ),
        )

        val afterItemDelete = parseCurrentMealBatches(batches)
        assertNotNull(afterItemDelete)
        assertFalse(afterItemDelete!!.sections.flattenLines().any { it.startsWith("Rice ") })

        val afterPlanDelete = parseCurrentMealBatches(
            batches + batch(
                delete("DailyPlanEntity", planId, revision(300)),
                upsert("DailyPlanEntity", planId, plan(planId, "Stale plan", 0, 1), revision(250)),
            ),
        )
        assertNull(afterPlanDelete)

        val afterPlanRestore = parseCurrentMealBatches(
            batches + batch(
                delete("DailyPlanEntity", planId, revision(300)),
                upsert("DailyPlanEntity", planId, plan(planId, "Restored later", 0, 1), revision(301)),
            ),
        )
        assertEquals("Restored later", afterPlanRestore?.name)
    }

    @Test
    fun excludesArchivedPlansAndMealsAndDisabledItems() {
        val planId = "plan-active"
        val mealId = "meal-archived"
        val result = parseCurrentMealBatches(
            listOf(
                batch(
                    upsert("DailyPlanEntity", "archived-plan", plan("archived-plan", "Hidden plan", 0, 1, archived = true)),
                    upsert("DailyPlanEntity", planId, plan(planId, "Active", 0, 2)),
                    upsert("DailyPlanMealSnapshotEntity", mealId, meal(mealId, planId, 0, "Archived meal", archived = "1")),
                    upsert("DailyPlanMealItemSnapshotEntity", "disabled", mealItem("disabled", mealId, 0, 10_000, 0, 0, enabled = false)),
                    upsert("DailyPlanFoodSnapshotEntity", "disabled", foodSnapshot("Disabled food")),
                ),
            ),
        )

        assertEquals("Active", result?.name)
        assertNull(result?.sections?.firstOrNull { it.title == "朝食" })
    }

    @Test
    fun omitsArchivedItemsAndItemsWithMissingDeletedOrArchivedChildSnapshots() {
        val planId = "plan-1"
        val mealId = "meal-1"
        val result = parseCurrentMealBatches(
            listOf(
                batch(
                    upsert("DailyPlanEntity", planId, plan(planId, "Active", 0, 1)),
                    upsert("DailyPlanMealSnapshotEntity", mealId, meal(mealId, planId, 0, "Breakfast")),
                    upsert("DailyPlanMealItemSnapshotEntity", "archived-item", mealItem("archived-item", mealId, 0, 10_000, 0, 0).put("IsArchived", 1)),
                    upsert("DailyPlanMealItemSnapshotEntity", "missing-child", mealItem("missing-child", mealId, 0, 20_000, 0, 1).put("Name", "Stale embedded name")),
                    upsert("DailyPlanMealItemSnapshotEntity", "deleted-child", mealItem("deleted-child", mealId, 0, 30_000, 0, 2)),
                    upsert("DailyPlanMealItemSnapshotEntity", "archived-child", mealItem("archived-child", mealId, 0, 40_000, 0, 3)),
                    upsert("DailyPlanMealItemSnapshotEntity", "archived-recipe", mealItem("archived-recipe", mealId, 1, 50_000, 0, 4)),
                    upsert("DailyPlanFoodSnapshotEntity", "archived-item", foodSnapshot("Food for archived item")),
                    delete("DailyPlanFoodSnapshotEntity", "deleted-child", revision(2)),
                    upsert("DailyPlanFoodSnapshotEntity", "archived-child", foodSnapshot("Archived food").put("IsArchived", true)),
                    upsert("DailyPlanRecipeSnapshotEntity", "archived-recipe", recipeSnapshot("Archived recipe").put("IsArchived", 1)),
                ),
            ),
        )

        assertEquals("Active", result?.name)
        assertEquals(listOf("料理: Breakfast"), result?.sections?.single()?.lines)
    }

    @Test
    fun ignoresWrongDatasetUnknownEntitiesAndMalformedChanges() {
        val wrongDataset = JSONObject()
            .put("DatasetId", "another-dataset")
            .put("Changes", JSONArray().put(upsert("DailyPlanEntity", "wrong", plan("wrong", "Wrong", 0, 1))))
        val malformedRevision = JSONObject()
            .put("entityType", "DailyPlanEntity")
            .put("rowKey", "bad")
            .put("changeKind", 0)
            .put("payloadJson", plan("bad", "Bad", 0, 1))
            .put("revision", JSONObject().put("editedUtcTicks", 100))
        val result = parseCurrentMealBatches(
            listOf(
                wrongDataset,
                batch(
                    upsert("FoodEntity", "food", JSONObject().put("Name", "Ignored"), revision(1)),
                    malformedRevision,
                    JSONObject().put("entityType", "DailyPlanEntity").put("rowKey", "unknown").put("changeKind", "merge"),
                ),
            ),
        )

        assertNull(result)
    }

    @Test
    fun preservesTickPrecisionOnJsonWireAndRetainsFirstExactTie() {
        val ticks = 638_900_000_000_000_001L
        val first = upsert("DailyPlanEntity", "plan-1", plan("plan-1", "First", 0, 1), revision(ticks))
        val tied = upsert("DailyPlanEntity", "plan-1", plan("plan-1", "Tie", 0, 1), revision(ticks))
        val older = upsert("DailyPlanEntity", "plan-1", plan("plan-1", "Older", 0, 1), revision(ticks - 1))
        val wire = JSONObject(batch(first, tied, older).toString())
        assertEquals("First", parseCurrentMealBatches(listOf(wire))?.name)
    }

    private fun plan(
        id: String,
        name: String,
        order: Int,
        created: Long,
        archived: Any = false,
        memo: String? = null,
    ): JSONObject = JSONObject()
        .put("Id", id)
        .put("Name", name)
        .put("DisplayOrder", order)
        .put("CreatedUtcTicks", created)
        .put("IsArchived", archived)
        .apply { memo?.let { put("Memo", it) } }

    private fun meal(
        id: String,
        planId: String,
        slot: Int,
        name: String,
        memo: String? = null,
        archived: Any = false,
    ): JSONObject = JSONObject()
        .put("Id", id)
        .put("DailyPlanId", planId)
        .put("Slot", slot)
        .put("Name", name)
        .put("IsArchived", archived)
        .apply { memo?.let { put("Memo", it) } }

    private fun mealItem(
        id: String,
        mealId: String,
        componentType: Int,
        amount: Long,
        unit: Int,
        order: Int,
        customUnit: String? = null,
        enabled: Any = true,
    ): JSONObject = JSONObject()
        .put("Id", id)
        .put("DailyPlanMealSnapshotId", mealId)
        .put("ComponentType", componentType)
        .put("StandardAmount", amount)
        .put("StandardUnit", unit)
        .put("DisplayOrder", order)
        .put("IsEnabled", enabled)
        .apply { customUnit?.let { put("StandardCustomUnitName", it) } }

    private fun foodSnapshot(name: String): JSONObject = JSONObject()
        .put("Name", name)

    private fun recipeSnapshot(name: String, memo: String? = null): JSONObject = JSONObject()
        .put("Name", name)
        .apply { memo?.let { put("Memo", it) } }

    private fun batch(vararg changes: JSONObject): JSONObject =
        JSONObject().put("Changes", JSONArray().apply { changes.forEach { put(it) } })

    private fun upsert(
        entityType: String,
        rowKey: String,
        payload: Any,
        revision: JSONObject = revision(1),
        kind: Any = 0,
    ): JSONObject = JSONObject()
        .put("EntityType", entityType)
        .put("RowKey", rowKey)
        .put("ChangeKind", kind)
        .put("PayloadJson", payload)
        .put("Revision", revision)

    private fun delete(
        entityType: String,
        rowKey: String,
        revision: JSONObject,
        kind: Any = 1,
    ): JSONObject = JSONObject()
        .put("EntityType", entityType)
        .put("RowKey", rowKey)
        .put("ChangeKind", kind)
        .put("Revision", revision)

    private fun revision(
        ticks: Any,
        deviceId: String = "device-a",
        sequence: Long = 1,
        operationId: String = "operation-1",
    ): JSONObject = JSONObject()
        .put("EditedUtcTicks", ticks)
        .put("DeviceId", deviceId)
        .put("DeviceSequence", sequence)
        .put("OperationId", operationId)
}

private fun List<MealPlanSection>.flattenLines(): List<String> = flatMap { it.lines }
