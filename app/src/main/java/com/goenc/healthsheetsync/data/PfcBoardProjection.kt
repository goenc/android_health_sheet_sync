package com.goenc.healthsheetsync.data

import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Read-only view models projected from the PC's synchronized EF snapshots. */
data class PfcBoard(
    val id: String,
    val name: String,
    val mode: Int,
    val baselineId: String?,
    val plans: List<PfcPlan>,
)

data class PfcPlan(
    val id: String,
    val name: String,
    val date: String?,
    val memo: String,
    val imageId: String?,
    val nutrition: PfcNutrition,
    val meals: List<PfcMeal>,
)

data class PfcMeal(
    val name: String,
    val slot: Int,
    val memo: String,
    val imageId: String?,
    val nutrition: PfcNutrition,
    val items: List<PfcItem>,
)

data class PfcItem(
    val name: String,
    val amount: String,
    val imageId: String?,
    val nutrition: PfcNutrition,
    val enabled: Boolean,
    val details: List<String>,
    val isMainDishCandidate: Boolean = false,
    val sourceEnabled: Boolean = true,
)

data class PfcNutrition(
    val kcal: BigDecimal,
    val p: BigDecimal,
    val f: BigDecimal,
    val c: BigDecimal,
    val purineMg: BigDecimal,
) {
    operator fun plus(other: PfcNutrition) = PfcNutrition(
        kcal + other.kcal, p + other.p, f + other.f, c + other.c, purineMg + other.purineMg,
    )

    operator fun minus(other: PfcNutrition) = PfcNutrition(
        kcal - other.kcal, p - other.p, f - other.f, c - other.c, purineMg - other.purineMg,
    )

    /** PC display rounding: kcal integer, other values to one decimal, midpoint away from zero. */
    val summary: String
        get() = "${kcal.rounded(0)} kcal  P ${p.rounded(1)}g  F ${f.rounded(1)}g  " +
            "C ${c.rounded(1)}g  プリン体 ${purineMg.rounded(1)}mg"

    /** Returns this value minus [other], matching the PC's comparison direction. */
    fun difference(other: PfcNutrition, compareRoundedSummary: Boolean = false): String {
        val left = if (compareRoundedSummary) roundedForDisplay() else this
        val right = if (compareRoundedSummary) other.roundedForDisplay() else other
        val delta = left - right
        // PC keeps known purine totals raw until after subtraction, even for meal differences.
        val purineDelta = purineMg - other.purineMg
        return "${delta.kcal.rounded(0)} kcal  P ${delta.p.rounded(1)}g  " +
            "F ${delta.f.rounded(1)}g  C ${delta.c.rounded(1)}g  " +
            "プリン体 ${purineDelta.rounded(1)}mg"
    }

    private fun roundedForDisplay() = PfcNutrition(
        kcal.setScale(0, ROUNDING), p.setScale(1, ROUNDING), f.setScale(1, ROUNDING),
        c.setScale(1, ROUNDING), purineMg.setScale(1, ROUNDING),
    )

    companion object {
        val ZERO = PfcNutrition(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
    }
}

/** Projects the synchronized rows into comparison-board order and recalculates PC nutrition. */
fun PfcSyncSnapshot.boards(): List<PfcBoard> = Projection(this).boards()

private class Projection(private val snapshot: PfcSyncSnapshot) {
    fun boards(): List<PfcBoard> = snapshot.all("ComparisonBoardEntity")
        .sortedWith(compareByDescending<JSONObject> { it.long("updatedUtcTicks") }
            .thenBy { it.requiredString("id") })
        .map { board ->
            val mode = board.int("calculationMode")
            require(mode in MODE_STANDARD..MODE_LOW) { "未対応の計算モード: $mode" }
            val boardId = board.requiredString("id")
            val planLinks = snapshot.all("ComparisonBoardPlanEntity")
                .filter { it.requiredString("comparisonBoardId").sameId(boardId) }
                .sortedWith(compareBy({ it.int("displayOrder") }, { it.requiredString("id") }))
            val plans = planLinks.mapNotNull { link ->
                val planId = link.requiredString("dailyPlanId")
                val planRow = snapshot.find("DailyPlanEntity", planId) ?: return@mapNotNull null
                if (planRow.boolean("isArchived")) return@mapNotNull null
                plan(planRow, mode)
            }
            val requestedBaseline = board.optionalString("selectedPlanId")
            val baseline = requestedBaseline?.takeIf { selected -> plans.any { it.id.sameId(selected) } }
                ?: plans.firstOrNull()?.id
            PfcBoard(
                id = boardId,
                name = board.string("name"),
                mode = mode,
                baselineId = baseline,
                plans = plans,
            )
        }

    private fun plan(row: JSONObject, mode: Int): PfcPlan {
        val id = row.requiredString("id")
        val sources = snapshot.all("DailyPlanMealSnapshotEntity")
            .filter { it.requiredString("dailyPlanId").sameId(id) }
            .sortedWith(compareBy({ it.int("slot") }, { it.long("createdUtcTicks") }, { it.requiredString("id") }))
            .map { row -> MealRows(row, snapshot.all("DailyPlanMealItemSnapshotEntity")
                .filter { it.requiredString("dailyPlanMealSnapshotId").sameId(row.requiredString("id")) }) }
        val meals = normalizeDrinks(sources).map { meal(it, mode) }

        var total = meals.fold(Raw.ZERO) { acc, meal -> acc + meal.raw }
        // The PC plan total counts the shared PreMealDrink once before each main meal.
        val preMealDrink = meals.filter { it.value.slot == SLOT_PRE_MEAL_DRINK }
            .fold(Raw.ZERO) { acc, meal -> acc + meal.raw }
        total += preMealDrink.scaleRatio(BigDecimal.valueOf(2), BigDecimal.ONE)

        return PfcPlan(
            id = id,
            name = row.string("name"),
            date = row.optionalString("targetDate"),
            memo = row.string("memo"),
            imageId = row.optionalString("representativeImageAssetId"),
            nutrition = total.toPublic(mode),
            meals = meals.map { it.value },
        )
    }

    private data class MealProjection(val value: PfcMeal, val raw: Raw)
    private data class MealRows(val row: JSONObject, val items: List<JSONObject>)

    /** Same read-time migration as PC DailyPlan.SeparateLegacyDrink / MergeLegacyDrinks. */
    private fun normalizeDrinks(sources: List<MealRows>): List<MealRows> {
        require(sources.map { it.row.int("slot") }.distinct().size == sources.size) { "食事枠が重複" }
        val bySlot = sources.associateBy { it.row.int("slot") }.toMutableMap()
        fun partition(source: MealRows, items: List<JSONObject>, slot: Int, selected: String?): MealRows {
            val row = JSONObject(source.row.toString()).put("slot", slot)
            row.put("selectedMainDishItemId", selected?.takeIf { id -> items.any { it.requiredString("id").sameId(id) } }
                ?: JSONObject.NULL)
            return MealRows(row, items)
        }
        listOf(0 to 6, 2 to 7, 3 to 8).forEach { (mainSlot, drinkSlot) ->
            val main = bySlot[mainSlot] ?: return@forEach
            val moving = main.items.filter { it.optBoolean("isPreMealDrink", false) }
            if (moving.isEmpty()) return@forEach
            val selected = main.row.optionalString("selectedMainDishItemId")
            bySlot[mainSlot] = partition(main, main.items - moving.toSet(), mainSlot, selected)
            val drink = bySlot[drinkSlot]
            bySlot[drinkSlot] = partition(drink ?: main, drink?.items.orEmpty() + moving, drinkSlot,
                drink?.row?.optionalString("selectedMainDishItemId") ?: selected)
        }
        val drinks = listOf(9, 6, 7, 8).mapNotNull { bySlot[it] }
        if (drinks.isNotEmpty()) {
            // Preserve duplicates within one card, but collapse equal entries across old meal cards.
            val unique = linkedMapOf<Pair<List<Any?>, Int>, JSONObject>()
            drinks.forEach { source ->
                val occurrences = mutableMapOf<List<Any?>, Int>()
                source.items.sortedBy { it.int("displayOrder") }.forEach { item ->
                    val id = item.requiredString("id")
                    val type = item.int("componentType")
                    val component = snapshot.find(if (type == COMPONENT_FOOD) "DailyPlanFoodSnapshotEntity" else "DailyPlanRecipeSnapshotEntity", id)
                    val sourceKey = if (type == COMPONENT_FOOD) "sourceFoodId" else "sourceRecipeId"
                    val key = listOf(
                        (component?.optionalString(sourceKey) ?: item.optionalString(sourceKey) ?: id).lowercase(),
                        item.long("standardAmount"), item.int("standardUnit"), item.optionalString("standardCustomUnitName")?.trim(),
                        item.optionalString("maximumAmount"), item.optionalString("maximumUnit"), item.optionalString("maximumCustomUnitName")?.trim(),
                        item.boolean("isEnabled"), item.boolean("isMainDishCandidate"),
                        source.row.optionalString("selectedMainDishItemId")?.sameId(id) == true,
                    )
                    val index = occurrences.getOrDefault(key, 0)
                    occurrences[key] = index + 1
                    unique.putIfAbsent(key to index, item)
                }
            }
            val items = unique.values.toList()
            val selected = drinks.firstNotNullOfOrNull { source ->
                source.row.optionalString("selectedMainDishItemId")?.takeIf { id -> items.any { it.requiredString("id").sameId(id) } }
            }
            bySlot[9] = partition(drinks.first(), items, 9, selected)
        }
        return listOf(0, 1, 2, 3, 4, 5, 9).mapNotNull { bySlot[it] }
    }

    private fun meal(source: MealRows, mode: Int): MealProjection {
        val row = source.row
        val selectedMain = row.optionalString("selectedMainDishItemId")
        val items = source.items
            .sortedWith(compareBy({ it.int("displayOrder") }, { it.requiredString("id") }))
            .map { item(it, mode, selectedMain) }
        val raw = items.fold(Raw.ZERO) { acc, item ->
            if (item.isSelected) acc + item.raw else acc
        }
        return MealProjection(
            PfcMeal(
                name = when (row.int("slot")) {
                    1 -> row.string("name").replace("朝間食", "午前中間食")
                    4 -> row.string("name").replace("夜間食", "午後間食").replace("昼間食", "午後間食")
                    9 -> "食前ドリンク（毎食前・1回分）"
                    else -> row.string("name")
                },
                slot = row.int("slot"),
                memo = row.string("memo"),
                imageId = row.optionalString("imageAssetId"),
                nutrition = raw.toPublic(mode),
                items = items.map { it.value },
            ),
            raw,
        )
    }

    private data class ItemProjection(val value: PfcItem, val raw: Raw, val isSelected: Boolean)

    private fun item(row: JSONObject, mode: Int, selectedMain: String?): ItemProjection {
        val id = row.requiredString("id")
        val componentType = row.int("componentType")
        val standardAmount = row.long("standardAmount")
        val standardUnit = row.int("standardUnit")
        val customUnit = row.optionalString("standardCustomUnitName")
        val enabled = row.boolean("isEnabled")
        val candidate = row.boolean("isMainDishCandidate")
        val selected = !candidate || (selectedMain != null && selectedMain.sameId(id))
        val rawAndDetails = when (componentType) {
            COMPONENT_FOOD -> {
                val food = snapshot.find("DailyPlanFoodSnapshotEntity", id)
                    ?: error("食品スナップショット $id が見つからない")
                val raw = foodNutrition(food).scale(
                    referenceRatio(standardAmount, standardUnit, customUnit, food, id),
                )
                raw to emptyList()
            }
            COMPONENT_RECIPE -> recipe(id, standardAmount, standardUnit, customUnit, mode)
            else -> error("未対応の食材種別: $componentType ($id)")
        }
        val contributes = enabled && selected
        val raw = if (contributes) rawAndDetails.first else Raw.ZERO
        val itemName = when (componentType) {
            COMPONENT_FOOD -> snapshot.find("DailyPlanFoodSnapshotEntity", id)!!.string("name")
            else -> snapshot.find("DailyPlanRecipeSnapshotEntity", id)!!.string("name")
        }
        val amount = formatQuantity(standardAmount, standardUnit, customUnit)
        return ItemProjection(
            PfcItem(itemName, amount, row.optionalString("imageAssetId")
                ?: snapshot.find(if (componentType == COMPONENT_FOOD) "DailyPlanFoodSnapshotEntity" else "DailyPlanRecipeSnapshotEntity", id)
                    ?.optionalString("imageAssetId"), rawAndDetails.first.toPublic(mode), contributes, rawAndDetails.second, candidate, enabled),
            raw,
            contributes,
        )
    }

    private fun recipe(
        itemId: String,
        amount: Long,
        unit: Int,
        customUnit: String?,
        mode: Int,
    ): Pair<Raw, List<String>> {
        val recipe = snapshot.find("DailyPlanRecipeSnapshotEntity", itemId)
            ?: error("レシピスナップショット $itemId が見つからない")
        val ingredients = snapshot.all("DailyPlanRecipeIngredientSnapshotEntity")
            .filter { it.requiredString("dailyPlanRecipeSnapshotId").sameId(itemId) }
            .sortedWith(compareBy({ it.int("displayOrder") }, { it.requiredString("id") }))
        val perRecipe: Raw
        val details: List<String>
        if (ingredients.isNotEmpty()) {
            perRecipe = ingredients.fold(Raw.ZERO) { total, ingredient ->
                if (!ingredient.boolean("isEnabled")) return@fold total
                val ingredientId = ingredient.requiredString("id")
                val ratio = referenceRatio(
                    ingredient.long("standardAmount"), ingredient.int("standardUnit"),
                    ingredient.optionalString("standardCustomUnitName"), ingredient, ingredientId,
                    isRecipeIngredient = true,
                )
                total + foodNutrition(ingredient).scale(ratio)
            }
            details = ingredients.map {
                val enabled = it.boolean("isEnabled")
                val suffix = if (enabled) "" else "（無効）"
                "${it.string("foodName")} ${formatQuantity(it.long("standardAmount"), it.int("standardUnit"), it.optionalString("standardCustomUnitName"))}$suffix"
            }
        } else {
            perRecipe = externalRecipeNutrition(recipe)
            details = emptyList()
        }

        val finished = recipe.long("finishedWeightMg")
        require(finished > 0) { "レシピ完成重量が0以下: $itemId" }
        val ratio = when (unit) {
            UNIT_GRAM -> ScaleRatio(BigDecimal.valueOf(amount), BigDecimal.valueOf(finished))
            UNIT_PIECE -> {
                val serving = recipe.long("standardServingWeightMg")
                require(serving > 0) { "レシピ標準一人分の重量が0以下: $itemId" }
                ScaleRatio(
                    BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(serving)),
                    MILLION.multiply(BigDecimal.valueOf(finished)),
                )
            }
            else -> error("レシピに未対応の単位: ${unitName(unit, customUnit)} ($itemId)")
        }
        val nutrition = if (ingredients.isEmpty()) perRecipe.scaleExact(ratio) else perRecipe.scale(ratio)
        return nutrition to details
    }

    private fun externalRecipeNutrition(recipe: JSONObject): Raw {
        val fields = listOf(
            "externalEnergyTenthsKcal", "externalProteinMilligrams", "externalFatMilligrams",
            "externalCarbohydrateMilligrams",
        )
        val present = fields.count { !recipe.isNull(it) && recipe.has(it) }
        if (present == 0) return Raw.ZERO
        require(present == fields.size) { "レシピ外部栄養の一部が欠落: ${recipe.requiredString("dailyPlanMealItemSnapshotId")}" }
        val energy = recipe.long("externalEnergyTenthsKcal")
        val range = if (energy == 0L) EnergyBand(0, 0) else EnergyBand(energy - 1, energy + 1)
        return Raw(
            range,
            recipe.long("externalProteinMilligrams"),
            recipe.long("externalFatMilligrams"),
            recipe.long("externalCarbohydrateMilligrams"),
            0, 0, 0,
        )
    }

    private fun referenceRatio(
        amount: Long,
        unit: Int,
        custom: String?,
        row: JSONObject,
        ownerId: String,
        isRecipeIngredient: Boolean = false,
    ): ScaleRatio {
        val referenceAmount = row.long("referenceAmount")
        val referenceUnit = row.int("referenceUnit")
        val referenceCustom = row.optionalString("referenceCustomUnitName")
        require(referenceAmount > 0) { "基準量が0以下: $ownerId" }
        if (sameUnit(unit, custom, referenceUnit, referenceCustom)) {
            return ScaleRatio(BigDecimal.valueOf(amount), BigDecimal.valueOf(referenceAmount))
        }
        val portionsType = if (isRecipeIngredient) "DailyPlanRecipePortionSnapshotEntity" else "DailyPlanFoodPortionSnapshotEntity"
        val fkName = if (isRecipeIngredient) "dailyPlanRecipeIngredientSnapshotId" else "dailyPlanMealItemSnapshotId"
        val portion = snapshot.all(portionsType).firstOrNull { candidate ->
            candidate.requiredString(fkName).sameId(ownerId) &&
                sameUnit(candidate.int("sourceUnit"), candidate.optionalString("sourceCustomUnitName"), unit, custom)
        } ?: error("単位換算が見つからない: $ownerId (${unitName(unit, custom)} -> ${unitName(referenceUnit, referenceCustom)})")
        val equivalentAmount = portion.long("standardEquivalentAmount")
        val equivalentUnit = portion.int("standardEquivalentUnit")
        val equivalentCustom = portion.optionalString("standardEquivalentCustomUnitName")
        require(sameUnit(equivalentUnit, equivalentCustom, referenceUnit, referenceCustom)) {
            "標準換算単位が食品基準単位と一致しない: $ownerId"
        }
        val sourceAmount = portion.long("sourceAmount")
        require(sourceAmount > 0) { "換算元数量が0以下: $ownerId" }
        // Portion.Convert divides first using .NET decimal precision, then FoodItem scales
        // the converted amount against its reference quantity. Preserve that two-step order.
        val convertedAmount = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(equivalentAmount))
            .divide(BigDecimal.valueOf(sourceAmount), DECIMAL_CONTEXT)
        return ScaleRatio(convertedAmount, BigDecimal.valueOf(referenceAmount))
    }

    private fun foodNutrition(row: JSONObject): Raw {
        val low = row.long("lowEnergyTenthsKcal")
        val high = row.long("highEnergyTenthsKcal")
        require(low <= high) { "エネルギー範囲が逆転: ${row.requiredString("dailyPlanMealItemSnapshotId")}" }
        var animal = row.long("animalPurineMicrogramsPerReference")
        var plant = row.long("plantPurineMicrogramsPerReference")
        var other = row.long("otherPurineMicrogramsPerReference")
        val purineTotal = row.long("purineMicrogramsPerReference")
        if (animal == 0L && plant == 0L && other == 0L && purineTotal != 0L) other = purineTotal
        return Raw(
            EnergyBand(low, high),
            row.long("proteinMilligrams"), row.long("fatMilligrams"), row.long("carbohydrateMilligrams"),
            animal, plant, other,
        )
    }
}

private data class EnergyBand(val low: Long, val high: Long) {
    private fun effective(): EnergyBand {
        if (low != high || low == 0L) return this
        return EnergyBand(scaleLong(low, BigDecimal("0.9")), scaleLong(high, BigDecimal("1.1")))
    }

    operator fun plus(other: EnergyBand): EnergyBand {
        val a = effective(); val b = other.effective()
        return EnergyBand(a.low + b.low, a.high + b.high)
    }

    fun scale(ratio: ScaleRatio): EnergyBand {
        val source = effective()
        return EnergyBand(scaleLong(source.low, ratio), scaleLong(source.high, ratio))
    }

    fun scaleExact(ratio: ScaleRatio) = EnergyBand(scaleLong(low, ratio), scaleLong(high, ratio))

    fun select(mode: Int): Long {
        val a = effective()
        return when (mode) {
            MODE_LOW -> a.low
            MODE_STANDARD -> scaleLong(a.low + a.high, BigDecimal("0.5"))
            MODE_HIGH -> a.high
            else -> error("未対応の計算モード: $mode")
        }
    }
}

private data class Raw(
    val energy: EnergyBand,
    val proteinMg: Long,
    val fatMg: Long,
    val carbohydrateMg: Long,
    val animalPurineUg: Long,
    val plantPurineUg: Long,
    val otherPurineUg: Long,
) {
    operator fun plus(other: Raw) = Raw(
        energy + other.energy,
        proteinMg + other.proteinMg,
        fatMg + other.fatMg,
        carbohydrateMg + other.carbohydrateMg,
        animalPurineUg + other.animalPurineUg,
        plantPurineUg + other.plantPurineUg,
        otherPurineUg + other.otherPurineUg,
    )

    fun scale(ratio: ScaleRatio) = Raw(
        energy.scale(ratio),
        scaleLong(proteinMg, ratio), scaleLong(fatMg, ratio), scaleLong(carbohydrateMg, ratio),
        scaleLong(animalPurineUg, ratio), scaleLong(plantPurineUg, ratio), scaleLong(otherPurineUg, ratio),
    )

    fun scaleExact(ratio: ScaleRatio) = Raw(
        energy.scaleExact(ratio),
        scaleLong(proteinMg, ratio), scaleLong(fatMg, ratio), scaleLong(carbohydrateMg, ratio),
        scaleLong(animalPurineUg, ratio), scaleLong(plantPurineUg, ratio), scaleLong(otherPurineUg, ratio),
    )

    fun scaleRatio(numerator: BigDecimal, denominator: BigDecimal) = scale(ScaleRatio(numerator, denominator))

    fun toPublic(mode: Int = MODE_STANDARD) = PfcNutrition(
        BigDecimal.valueOf(energy.select(mode)).divide(TEN),
        BigDecimal.valueOf(proteinMg).divide(THOUSAND),
        BigDecimal.valueOf(fatMg).divide(THOUSAND),
        BigDecimal.valueOf(carbohydrateMg).divide(THOUSAND),
        BigDecimal.valueOf(animalPurineUg + plantPurineUg + otherPurineUg).divide(THOUSAND),
    )

    companion object {
        val ZERO = Raw(EnergyBand(0, 0), 0, 0, 0, 0, 0, 0)
    }
}

private fun JSONObject.requiredString(name: String): String {
    require(has(name) && !isNull(name)) { "必須列が欠落: $name" }
    return get(name).toString()
}

private fun JSONObject.optionalString(name: String): String? =
    if (!has(name) || isNull(name)) null else get(name).toString()

private fun JSONObject.string(name: String): String =
    if (!has(name) || isNull(name)) "" else get(name).toString()

private fun JSONObject.long(name: String): Long {
    require(has(name) && !isNull(name)) { "必須数値列が欠落: $name" }
    return when (val value = get(name)) {
        is Number -> value.toLong()
        else -> value.toString().toLongOrNull() ?: error("数値列を解釈できない: $name")
    }
}

private fun JSONObject.int(name: String): Int = long(name).toInt()

private fun JSONObject.boolean(name: String): Boolean {
    require(has(name) && !isNull(name)) { "必須真偽列が欠落: $name" }
    return when (val value = get(name)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value.toString().toBooleanStrictOrNull() ?: error("真偽列を解釈できない: $name")
    }
}

private fun sameUnit(left: Int, leftCustom: String?, right: Int, rightCustom: String?): Boolean =
    left == right && (left != UNIT_CUSTOM || leftCustom.equals(rightCustom, ignoreCase = true))

private fun String.sameId(other: String): Boolean = equals(other, ignoreCase = true)

private fun formatQuantity(amount: Long, unit: Int, custom: String?): String =
    "${formatStoredAmount(amount, unit)}${unitName(unit, custom)}"

private fun formatStoredAmount(amount: Long, unit: Int): String = when (unit) {
    UNIT_GRAM, UNIT_MILLILITER -> BigDecimal.valueOf(amount).divide(THOUSAND).stripTrailingZeros().toPlainString()
    else -> BigDecimal.valueOf(amount).divide(MILLION).stripTrailingZeros().toPlainString()
}

private fun unitName(unit: Int, custom: String?): String = when (unit) {
    UNIT_GRAM -> "g"
    UNIT_MILLILITER -> "ml"
    UNIT_PIECE -> "個"
    3 -> "パック"
    4 -> "切れ"
    5 -> "カップ"
    6 -> "本"
    7 -> "枚"
    UNIT_CUSTOM -> custom?.takeIf { it.isNotBlank() } ?: error("カスタム単位名が空")
    else -> error("未対応の単位: $unit")
}

private fun BigDecimal.rounded(scale: Int): String =
    setScale(scale, ROUNDING).stripTrailingZeros().toPlainString()

private fun scaleLong(value: Long, ratio: ScaleRatio): Long =
    BigDecimal.valueOf(value).multiply(ratio.numerator)
        .divide(ratio.denominator, 0, ROUNDING).longValueExact()

private fun scaleLong(value: Long, ratio: BigDecimal): Long =
    BigDecimal.valueOf(value).multiply(ratio).setScale(0, ROUNDING).longValueExact()

private data class ScaleRatio(val numerator: BigDecimal, val denominator: BigDecimal) {
    init { require(denominator.signum() > 0) { "基準量が0以下" } }
}

private const val MODE_HIGH = 1
private const val MODE_LOW = 2
private const val MODE_STANDARD = 0
private const val SLOT_PRE_MEAL_DRINK = 9
private const val COMPONENT_FOOD = 0
private const val COMPONENT_RECIPE = 1
private const val UNIT_GRAM = 0
private const val UNIT_MILLILITER = 1
private const val UNIT_PIECE = 2
private const val UNIT_CUSTOM = 8
private val ROUNDING = RoundingMode.HALF_UP
private val DECIMAL_CONTEXT = MathContext(28, RoundingMode.HALF_EVEN)
private val TEN = BigDecimal.TEN
private val THOUSAND = BigDecimal.valueOf(1_000)
private val MILLION = BigDecimal.valueOf(1_000_000)
