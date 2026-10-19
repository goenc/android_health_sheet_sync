package com.goenc.healthsheetsync.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class PfcBoardProjectionTest {
    @Test
    fun `standard high low modes and shared drink total match pc rules`() {
        val standard = fixture(mode = 0).boards().single().plans.single()
        val high = fixture(mode = 1).boards().single().plans.single()
        val low = fixture(mode = 2).boards().single().plans.single()

        assertDecimal("225", standard.nutrition.kcal) // food 150 + shared drink 25 x 3
        assertDecimal("320", high.nutrition.kcal) // food 200 + shared drink 40 x 3
        assertDecimal("130", low.nutrition.kcal) // food 100 + shared drink 10 x 3
        assertDecimal("10", standard.nutrition.p)
        assertDecimal("1.5", standard.nutrition.purineMg)
        assertEquals(0, standard.meals.first { it.slot == 0 }.nutrition.kcal.compareTo(BigDecimal("150")))
    }

    @Test
    fun `missing food snapshot fails instead of showing zero`() {
        val snapshot = fixture(mode = 0, includeFood = false)
        assertThrows(IllegalStateException::class.java) { snapshot.boards() }
    }

    @Test
    fun `portion conversion uses standard equivalent without maximum values`() {
        val rows = mutableRows(fixture(mode = 0))
        val item = rows.getValue("DailyPlanMealItemSnapshotEntity").getValue("food-item")
        item.put("standardAmount", 1_000_000L) // one piece
        item.put("standardUnit", 2)
        rows["DailyPlanFoodPortionSnapshotEntity"] = mapOf("portion" to JSONObject().apply {
            put("id", "portion")
            put("dailyPlanMealItemSnapshotId", "food-item")
            put("sourceAmount", 2_000_000L) // two pieces
            put("sourceUnit", 2)
            put("sourceCustomUnitName", JSONObject.NULL)
            put("standardEquivalentAmount", 50_000L) // 50 g
            put("standardEquivalentUnit", 0)
            put("standardEquivalentCustomUnitName", JSONObject.NULL)
        })

        val food = snapshot(rows).boards().single().plans.single().meals.first { it.slot == 0 }.items.single()
        assertEquals("1個", food.amount)
        assertDecimal("37.5", food.nutrition.kcal)
        assertDecimal("2.5", food.nutrition.p)
        assertDecimal("0.375", food.nutrition.purineMg)
    }

    @Test
    fun `quantity scaling rounds an exact half milligram away from zero`() {
        val rows = mutableRows(fixture(mode = 0))
        val item = rows.getValue("DailyPlanMealItemSnapshotEntity").getValue("food-item")
        item.put("standardAmount", 1_000_000L)
        item.put("standardUnit", 2)
        val foodRows = rows.getValue("DailyPlanFoodSnapshotEntity").toMutableMap()
        foodRows.getValue("food-item").put("proteinMilligrams", 1L)
        foodRows.getValue("food-item").put("animalProteinMilligrams", 1L)
        rows["DailyPlanFoodSnapshotEntity"] = foodRows
        rows["DailyPlanFoodPortionSnapshotEntity"] = mapOf("portion" to JSONObject().apply {
            put("id", "portion"); put("dailyPlanMealItemSnapshotId", "food-item")
            put("sourceAmount", 2_000_000L); put("sourceUnit", 2)
            put("sourceCustomUnitName", JSONObject.NULL)
            put("standardEquivalentAmount", 100_000L); put("standardEquivalentUnit", 0)
            put("standardEquivalentCustomUnitName", JSONObject.NULL)
        })

        val food = snapshot(rows).boards().single().plans.single().meals.first { it.slot == 0 }.items.single()
        assertDecimal("0.001", food.nutrition.p) // .NET MidpointRounding.AwayFromZero
    }

    @Test
    fun `recipe nutrition scales ingredients by recipe weight then serving quantity`() {
        val rows = mutableRows(fixture(mode = 0))
        val item = rows.getValue("DailyPlanMealItemSnapshotEntity").getValue("food-item")
        item.put("componentType", 1)
        item.put("standardAmount", 50_000L) // 50 g of a 200 g recipe
        rows["DailyPlanRecipeSnapshotEntity"] = mapOf("food-item" to JSONObject().apply {
            put("dailyPlanMealItemSnapshotId", "food-item")
            put("sourceRecipeId", "source-recipe")
            put("name", "Recipe")
            put("imageAssetId", JSONObject.NULL)
            put("finishedWeightMg", 200_000L)
            put("standardServingWeightMg", 100_000L)
            put("externalEnergyTenthsKcal", JSONObject.NULL)
            put("externalProteinMilligrams", JSONObject.NULL)
            put("externalFatMilligrams", JSONObject.NULL)
            put("externalCarbohydrateMilligrams", JSONObject.NULL)
        })
        rows["DailyPlanRecipeIngredientSnapshotEntity"] = mapOf("ingredient" to JSONObject().apply {
            put("id", "ingredient")
            put("dailyPlanRecipeSnapshotId", "food-item")
            put("sourceFoodId", "source-food")
            put("foodName", "Ingredient")
            put("imageAssetId", JSONObject.NULL)
            put("referenceAmount", 100_000L)
            put("referenceUnit", 0)
            put("referenceCustomUnitName", JSONObject.NULL)
            put("energyTenthsKcal", 1_500L)
            put("lowEnergyTenthsKcal", 1_000L)
            put("highEnergyTenthsKcal", 2_000L)
            put("proteinMilligrams", 10_000L)
            put("animalProteinMilligrams", 10_000L)
            put("plantProteinMilligrams", 0L)
            put("otherProteinMilligrams", 0L)
            put("fatMilligrams", 0L)
            put("carbohydrateMilligrams", 0L)
            put("purineMicrogramsPerReference", 1_500L)
            put("animalPurineMicrogramsPerReference", 1_500L)
            put("plantPurineMicrogramsPerReference", 0L)
            put("otherPurineMicrogramsPerReference", 0L)
            put("standardAmount", 200_000L)
            put("standardUnit", 0)
            put("standardCustomUnitName", JSONObject.NULL)
            put("isEnabled", true)
            put("displayOrder", 0)
        })

        val recipe = snapshot(rows).boards().single().plans.single().meals.first { it.slot == 0 }.items.single()
        assertEquals("Recipe", recipe.name)
        assertDecimal("75", recipe.nutrition.kcal)
        assertDecimal("5", recipe.nutrition.p)
        assertEquals(listOf("Ingredient 200g"), recipe.details)
    }

    @Test
    fun `only selected enabled main dish contributes but item values stay inspectable`() {
        val rows = mutableRows(fixture(mode = 0))
        val meal = rows.getValue("DailyPlanMealSnapshotEntity").getValue("breakfast")
        meal.put("selectedMainDishItemId", "food-item")
        val items = rows.getValue("DailyPlanMealItemSnapshotEntity").toMutableMap()
        items.getValue("food-item").put("isMainDishCandidate", true)
        items["other-item"] = itemRow("other-item", "breakfast").apply {
            put("isMainDishCandidate", true)
        }
        rows["DailyPlanMealItemSnapshotEntity"] = items
        val foods = rows.getValue("DailyPlanFoodSnapshotEntity").toMutableMap()
        foods["other-item"] = foodRow("other-item", 500, 500, protein = 2_000, purine = 700)
        rows["DailyPlanFoodSnapshotEntity"] = foods

        val breakfast = snapshot(rows).boards().single().plans.single().meals.first { it.slot == 0 }
        assertDecimal("150", breakfast.nutrition.kcal)
        val skipped = breakfast.items.single { it.name == "other-item" }
        assertEquals(false, skipped.enabled)
        assertEquals(true, skipped.isMainDishCandidate)
        assertEquals(true, skipped.sourceEnabled)
        assertDecimal("50", skipped.nutrition.kcal)
    }

    @Test
    fun `normalization removes missing and archived plans and repairs baseline`() {
        val rows = mutableRows(fixture(mode = 0))
        val board = rows.getValue("ComparisonBoardEntity").getValue("board")
        board.put("selectedPlanId", "archived")
        val links = rows.getValue("ComparisonBoardPlanEntity").toMutableMap()
        links["archived-link"] = JSONObject().apply {
            put("id", "archived-link"); put("comparisonBoardId", "board")
            put("dailyPlanId", "archived"); put("displayOrder", 1)
        }
        links["missing-link"] = JSONObject().apply {
            put("id", "missing-link"); put("comparisonBoardId", "board")
            put("dailyPlanId", "missing"); put("displayOrder", 2)
        }
        rows["ComparisonBoardPlanEntity"] = links
        val plans = rows.getValue("DailyPlanEntity").toMutableMap()
        plans["archived"] = JSONObject().apply {
            put("id", "archived"); put("name", "Archived"); put("isArchived", true)
            put("targetDate", JSONObject.NULL); put("representativeImageAssetId", JSONObject.NULL); put("memo", "")
        }
        rows["DailyPlanEntity"] = plans

        val boardResult = snapshot(rows).boards().single()
        assertEquals(listOf("plan"), boardResult.plans.map { it.id })
        assertEquals("plan", boardResult.baselineId)
    }

    @Test
    fun `difference supports raw daily and rounded meal comparison while purine stays raw`() {
        val comparison = PfcNutrition(
            BigDecimal("1.51"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("1.051"),
        )
        val baseline = PfcNutrition(
            BigDecimal("1.49"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("1.049"),
        )

        assertEquals(true, comparison.difference(baseline).startsWith("0 kcal"))
        val meal = comparison.difference(baseline, compareRoundedSummary = true)
        assertEquals(true, meal.startsWith("1 kcal"))
        assertEquals(true, meal.endsWith("プリン体 0mg"))
    }

    private fun fixture(mode: Int, includeFood: Boolean = true): PfcSyncSnapshot {
        fun row(vararg values: Pair<String, Any?>) = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }

        val breakfastItem = "food-item"
        val drinkItem = "drink-item"
        val rows = mutableMapOf<String, Map<String, JSONObject>>()
        rows["ComparisonBoardEntity"] = mapOf("board" to row(
            "id" to "board", "name" to "Board", "calculationMode" to mode, "selectedPlanId" to "plan",
            "updatedUtcTicks" to 1L,
        ))
        rows["ComparisonBoardPlanEntity"] = mapOf("board-plan" to row(
            "id" to "board-plan", "comparisonBoardId" to "board", "dailyPlanId" to "plan", "displayOrder" to 0,
        ))
        rows["DailyPlanEntity"] = mapOf("plan" to row(
            "id" to "plan", "name" to "Plan", "targetDate" to "2026-10-19",
            "representativeImageAssetId" to null, "memo" to "", "isArchived" to false,
        ))
        rows["DailyPlanMealSnapshotEntity"] = mapOf(
            "breakfast" to row(
                "id" to "breakfast", "dailyPlanId" to "plan", "slot" to 0, "name" to "Breakfast",
                "memo" to "", "imageAssetId" to null, "selectedMainDishItemId" to null,
                "createdUtcTicks" to 1L,
            ),
            "drink" to row(
                "id" to "drink", "dailyPlanId" to "plan", "slot" to 9, "name" to "PreMealDrink",
                "memo" to "", "imageAssetId" to null, "selectedMainDishItemId" to null,
                "createdUtcTicks" to 2L,
            ),
        )
        rows["DailyPlanMealItemSnapshotEntity"] = mapOf(
            breakfastItem to itemRow(breakfastItem, "breakfast"),
            drinkItem to itemRow(drinkItem, "drink"),
        )
        if (includeFood) {
            rows["DailyPlanFoodSnapshotEntity"] = mapOf(
                breakfastItem to foodRow(breakfastItem, lowKcal = 1_000, highKcal = 2_000,
                    protein = 10_000, purine = 1_500),
                drinkItem to foodRow(drinkItem, lowKcal = 100, highKcal = 400,
                    protein = 0, purine = 0),
            )
        }
        return PfcSyncSnapshot(rows)
    }

    private fun mutableRows(snapshot: PfcSyncSnapshot): MutableMap<String, Map<String, JSONObject>> =
        snapshot.rows.mapValues { (_, entityRows) ->
            entityRows.mapValues { (_, json) -> JSONObject(json.toString()) }.toMutableMap()
        }.toMutableMap()

    private fun snapshot(rows: Map<String, Map<String, JSONObject>>) = PfcSyncSnapshot(rows)

    private fun itemRow(id: String, mealId: String) = JSONObject().apply {
        put("id", id)
        put("dailyPlanMealSnapshotId", mealId)
        put("componentType", 0)
        put("standardAmount", 100_000L) // 100 g in the PC's scaled unit
        put("standardUnit", 0)
        put("standardCustomUnitName", JSONObject.NULL)
        put("isEnabled", true)
        put("displayOrder", 0)
        put("isMainDishCandidate", false)
        put("isPreMealDrink", mealId == "drink")
    }

    private fun foodRow(id: String, lowKcal: Long, highKcal: Long, protein: Long, purine: Long) = JSONObject().apply {
        put("dailyPlanMealItemSnapshotId", id)
        put("name", id)
        put("imageAssetId", JSONObject.NULL)
        put("referenceAmount", 100_000L)
        put("referenceUnit", 0)
        put("referenceCustomUnitName", JSONObject.NULL)
        put("energyTenthsKcal", (lowKcal + highKcal) / 2)
        put("lowEnergyTenthsKcal", lowKcal)
        put("highEnergyTenthsKcal", highKcal)
        put("proteinMilligrams", protein)
        put("animalProteinMilligrams", protein)
        put("plantProteinMilligrams", 0L)
        put("otherProteinMilligrams", 0L)
        put("fatMilligrams", 0L)
        put("carbohydrateMilligrams", 0L)
        put("purineMicrogramsPerReference", purine)
        put("animalPurineMicrogramsPerReference", purine)
        put("plantPurineMicrogramsPerReference", 0L)
        put("otherPurineMicrogramsPerReference", 0L)
    }

    private fun assertDecimal(expected: String, actual: BigDecimal) {
        assertEquals(0, actual.compareTo(BigDecimal(expected)))
    }
}
