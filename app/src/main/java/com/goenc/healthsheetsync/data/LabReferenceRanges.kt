package com.goenc.healthsheetsync.data

import java.math.BigDecimal

/**
 * An item-specific numeric reference range.
 *
 * At least one bound is required. A missing bound represents an open-ended
 * range; a [minimumInclusive] or [maximumInclusive] value is only meaningful
 * when its corresponding bound is present.
 */
internal data class LabReferenceRange(
    val minimum: BigDecimal? = null,
    val maximum: BigDecimal? = null,
    val minimumInclusive: Boolean = true,
    val maximumInclusive: Boolean = true,
) {
    init {
        require(minimum != null || maximum != null) {
            "基準値は下限または上限の少なくとも一方を指定してください"
        }
        // BigDecimal has no NaN or infinity representation. Values reaching
        // this model are therefore finite by construction.
        require(minimum == null || maximum == null || minimum.compareTo(maximum) <= 0) {
            "基準値の下限は上限以下にしてください"
        }
    }

    /** A compact Japanese representation suitable for the common UI display. */
    val displayText: String
        get() = when {
            minimum != null && maximum != null && minimumInclusive && maximumInclusive ->
                "${minimum.toPlainString()}〜${maximum.toPlainString()}"
            minimum != null && maximum != null ->
                "${minimum.toPlainString()}${if (minimumInclusive) "以上" else "超"}〜" +
                    "${maximum.toPlainString()}${if (maximumInclusive) "以下" else "未満"}"
            minimum != null -> "${minimum.toPlainString()}${if (minimumInclusive) "以上" else "超"}"
            else -> "${maximum!!.toPlainString()}${if (maximumInclusive) "以下" else "未満"}"
        }
}

/**
 * Finds a safe initial range candidate for [itemName] from stored results.
 *
 * Blank reference ranges are ignored. Every non-blank range for the item must
 * be parseable and semantically identical, including a sex qualifier when it
 * is present. This prevents automatically selecting one of several sex- or
 * condition-specific ranges.
 */
internal fun findInitialLabReferenceRange(
    itemName: String,
    records: Iterable<LabResult>,
): LabReferenceRange? {
    val normalizedItemName = itemName.trim()
    if (normalizedItemName.isEmpty()) return null

    val references = records
        .asSequence()
        .filter { it.itemName == normalizedItemName }
        .map { it.referenceRange.replace(FULL_WIDTH_SPACE, ' ').trim() }
        .filter(String::isNotEmpty)
        .toList()
    if (references.isEmpty()) return null

    val parsed = references.map { parseReferenceRange(it) ?: return null }
    val first = parsed.first()
    if (parsed.drop(1).any { !it.isSemanticallyEqualTo(first) }) return null
    return first.range
}

/**
 * Classifies a result against a configured range.
 *
 * Returns `"H"` or `"L"` for values outside the upper or lower bound,
 * respectively, an empty string for values inside the range, and `null` when
 * the value is not a plain numeric value. Values containing inequalities or
 * qualitative text are intentionally left unclassified.
 */
internal fun classifyLabResultFlag(
    result: LabResult,
    referenceRange: LabReferenceRange,
): String? = classifyLabValueFlag(result.value, referenceRange)

/**
 * Applies item-specific ranges to a result list without changing source
 * values. A configured range recalculates only plain numeric values. For
 * qualitative or inequality values the stored flag is retained because it
 * cannot be safely inferred. An explicitly disabled item clears its flag. A
 * present map key with a null value is also an explicit disabled override;
 * an absent key means that no range is configured.
 */
internal fun applyLabReferenceRanges(
    records: Iterable<LabResult>,
    effectiveRanges: Map<String, LabReferenceRange?>,
    disabledItems: Set<String> = emptySet(),
): List<LabResult> = records.map { record ->
    val hasExplicitNullOverride =
        effectiveRanges.containsKey(record.itemName) && effectiveRanges[record.itemName] == null
    when {
        record.itemName in disabledItems || hasExplicitNullOverride -> record.copy(flag = "")
        else -> {
            val range = effectiveRanges[record.itemName]
            val flag = range?.let { classifyLabResultFlag(record, it) }
            if (flag == null) record else record.copy(flag = flag)
        }
    }
}

private fun classifyLabValueFlag(
    value: String,
    referenceRange: LabReferenceRange,
): String? {
    val numericValue = runCatching { BigDecimal(value.trim()) }.getOrNull() ?: return null

    referenceRange.minimum?.let { minimum ->
        val comparison = numericValue.compareTo(minimum)
        if (comparison < 0 || (comparison == 0 && !referenceRange.minimumInclusive)) {
            return "L"
        }
    }
    referenceRange.maximum?.let { maximum ->
        val comparison = numericValue.compareTo(maximum)
        if (comparison > 0 || (comparison == 0 && !referenceRange.maximumInclusive)) {
            return "H"
        }
    }
    return ""
}

private data class ParsedReferenceRange(
    val range: LabReferenceRange,
    val qualifier: ReferenceRangeQualifier?,
) {
    fun isSemanticallyEqualTo(other: ParsedReferenceRange): Boolean =
        qualifier == other.qualifier &&
            minimumsEqual(range.minimum, other.range.minimum) &&
            maximumsEqual(range.maximum, other.range.maximum) &&
            range.minimumInclusive == other.range.minimumInclusive &&
            range.maximumInclusive == other.range.maximumInclusive

    private fun minimumsEqual(left: BigDecimal?, right: BigDecimal?): Boolean =
        decimalsEqual(left, right)

    private fun maximumsEqual(left: BigDecimal?, right: BigDecimal?): Boolean =
        decimalsEqual(left, right)
}

private enum class ReferenceRangeQualifier {
    MALE,
    FEMALE,
}

private const val DECIMAL_NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"

private val SEX_PREFIX_PATTERN = Regex(
    """^(男性|女性|男|女)\s*[:：]?\s*(.+)$"""
)
private val RANGE_PATTERN = Regex(
    """^($DECIMAL_NUMBER)(以上|超)?\s*(?:〜|～|~|[-−–—])\s*($DECIMAL_NUMBER)(以下|未満)?$"""
)
private val SYMBOL_OPERATOR_PATTERN = Regex(
    """^(<=|≥|>=|≤|<|>)\s*($DECIMAL_NUMBER)$"""
)
private val WORD_OPERATOR_PATTERN = Regex(
    """^($DECIMAL_NUMBER)\s*(以下|未満|以上|超|超える)$"""
)

private fun parseReferenceRange(text: String): ParsedReferenceRange? {
    val normalized = text.replace(FULL_WIDTH_SPACE, ' ').trim()
    if (normalized.isEmpty()) return null

    val sexPrefix = SEX_PREFIX_PATTERN.matchEntire(normalized)
    val qualifier = sexPrefix?.groupValues?.get(1)?.let(::parseQualifier)
    val body = sexPrefix?.groupValues?.get(2)?.trim() ?: normalized
    if (sexPrefix != null && qualifier == null) return null

    val range = parseRangeBody(body) ?: return null
    return ParsedReferenceRange(range, qualifier)
}

private fun parseQualifier(prefix: String): ReferenceRangeQualifier? = when (prefix) {
    "男", "男性" -> ReferenceRangeQualifier.MALE
    "女", "女性" -> ReferenceRangeQualifier.FEMALE
    else -> null
}

private fun parseRangeBody(body: String): LabReferenceRange? {
    RANGE_PATTERN.matchEntire(body)?.let { match ->
        val minimum = parseDecimal(match.groupValues[1]) ?: return null
        val maximum = parseDecimal(match.groupValues[3]) ?: return null
        return runCatching {
            LabReferenceRange(
                minimum = minimum,
                maximum = maximum,
                minimumInclusive = match.groupValues[2] != "超",
                maximumInclusive = match.groupValues[4] != "未満",
            )
        }.getOrNull()
    }

    SYMBOL_OPERATOR_PATTERN.matchEntire(body)?.let { match ->
        val operator = match.groupValues[1]
        val value = parseDecimal(match.groupValues[2]) ?: return null
        return when (operator) {
            "<", "≤", "<=" -> runCatching {
                LabReferenceRange(maximum = value, maximumInclusive = operator != "<")
            }.getOrNull()
            ">", "≥", ">=" -> runCatching {
                LabReferenceRange(minimum = value, minimumInclusive = operator != ">")
            }.getOrNull()
            else -> null
        }
    }

    WORD_OPERATOR_PATTERN.matchEntire(body)?.let { match ->
        val value = parseDecimal(match.groupValues[1]) ?: return null
        return when (match.groupValues[2]) {
            "以下" -> runCatching { LabReferenceRange(maximum = value) }.getOrNull()
            "未満" -> runCatching {
                LabReferenceRange(maximum = value, maximumInclusive = false)
            }.getOrNull()
            "以上" -> runCatching { LabReferenceRange(minimum = value) }.getOrNull()
            "超", "超える" -> runCatching {
                LabReferenceRange(minimum = value, minimumInclusive = false)
            }.getOrNull()
            else -> null
        }
    }

    return null
}

private fun parseDecimal(text: String): BigDecimal? =
    runCatching { BigDecimal(text) }.getOrNull()

private fun decimalsEqual(left: BigDecimal?, right: BigDecimal?): Boolean = when {
    left == null || right == null -> left == null && right == null
    else -> left.compareTo(right) == 0
}

private const val FULL_WIDTH_SPACE = '\u3000'
