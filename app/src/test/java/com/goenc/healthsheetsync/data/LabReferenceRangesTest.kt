package com.goenc.healthsheetsync.data

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LabReferenceRangesTest {
    @Test
    fun modelAllowsOneSidedFiniteBoundsAndRejectsInvalidOrder() {
        assertEquals(BigDecimal("0.4"), LabReferenceRange(maximum = BigDecimal("0.4")).maximum)
        assertEquals(BigDecimal("5.0"), LabReferenceRange(minimum = BigDecimal("5.0")).minimum)
        assertThrows(IllegalArgumentException::class.java) {
            LabReferenceRange(minimum = BigDecimal("2"), maximum = BigDecimal("1"))
        }
        assertThrows(IllegalArgumentException::class.java) { LabReferenceRange() }
    }

    @Test
    fun candidateReadsRepeatedRangeAndIgnoresBlankRows() {
        val candidate = findInitialLabReferenceRange(
            itemName = "血糖",
            records = listOf(
                result(item = "血糖", referenceRange = "男 3.6〜7.0"),
                result(item = "血糖", referenceRange = ""),
                result(item = "血糖", referenceRange = "男性 3.60～7.00"),
                result(item = "尿蛋白", referenceRange = "0.0以下"),
            ),
        )

        assertEquals(BigDecimal("3.6"), candidate?.minimum)
        assertEquals(BigDecimal("7.0"), candidate?.maximum)
        assertEquals(true, candidate?.minimumInclusive)
        assertEquals(true, candidate?.maximumInclusive)
    }

    @Test
    fun candidateReadsInclusiveAndExclusiveOneSidedRanges() {
        val inclusiveUpper = findInitialLabReferenceRange(
            "クレアチニン",
            listOf(result(item = "クレアチニン", referenceRange = "0.4以下")),
        )
        val exclusiveUpper = findInitialLabReferenceRange(
            "HbA1c",
            listOf(result(item = "HbA1c", referenceRange = "5.0未満")),
        )
        val inclusiveLower = findInitialLabReferenceRange(
            "下限",
            listOf(result(item = "下限", referenceRange = "3.6以上")),
        )
        val mixedRange = findInitialLabReferenceRange(
            "混在",
            listOf(result(item = "混在", referenceRange = "3.6以上〜7.0未満")),
        )

        assertEquals(BigDecimal("0.4"), inclusiveUpper?.maximum)
        assertEquals(true, inclusiveUpper?.maximumInclusive)
        assertEquals(BigDecimal("5.0"), exclusiveUpper?.maximum)
        assertEquals(false, exclusiveUpper?.maximumInclusive)
        assertEquals(BigDecimal("3.6"), inclusiveLower?.minimum)
        assertEquals(true, inclusiveLower?.minimumInclusive)
        assertEquals(BigDecimal("3.6"), mixedRange?.minimum)
        assertEquals(true, mixedRange?.minimumInclusive)
        assertEquals(BigDecimal("7.0"), mixedRange?.maximum)
        assertEquals(false, mixedRange?.maximumInclusive)
    }

    @Test
    fun candidateRejectsAmbiguousSexRangesAndUnparseableText() {
        assertNull(findInitialLabReferenceRange(
            "血糖",
            listOf(
                result(item = "血糖", referenceRange = "男 3.6〜7.0"),
                result(item = "血糖", referenceRange = "女 3.6〜7.0"),
            ),
        ))
        assertNull(findInitialLabReferenceRange(
            "血糖",
            listOf(
                result(item = "血糖", referenceRange = "3.6〜7.0"),
                result(item = "血糖", referenceRange = "8.0〜20.0"),
            ),
        ))
        assertNull(findInitialLabReferenceRange(
            "血糖",
            listOf(result(item = "血糖", referenceRange = "基準値不明")),
        ))
    }

    @Test
    fun candidateSupportsSymbolicBoundsAndRejectsBareNumber() {
        val upper = findInitialLabReferenceRange(
            "上限",
            listOf(result(item = "上限", referenceRange = "<= 79")),
        )
        val lower = findInitialLabReferenceRange(
            "下限",
            listOf(result(item = "下限", referenceRange = "> 18.0")),
        )

        assertEquals(BigDecimal("79"), upper?.maximum)
        assertEquals(true, upper?.maximumInclusive)
        assertEquals(BigDecimal("18.0"), lower?.minimum)
        assertEquals(false, lower?.minimumInclusive)
        assertNull(findInitialLabReferenceRange(
            "単独値",
            listOf(result(item = "単独値", referenceRange = "5.0")),
        ))
    }

    @Test
    fun classificationUsesExactDecimalComparisonAndBoundaryInclusion() {
        val range = LabReferenceRange(
            minimum = BigDecimal("0.10"),
            maximum = BigDecimal("1.00"),
            minimumInclusive = false,
            maximumInclusive = true,
        )

        assertEquals("L", classifyLabResultFlag(result(value = "0.10"), range))
        assertEquals("", classifyLabResultFlag(result(value = "0.1000000000000000000001"), range))
        assertEquals("", classifyLabResultFlag(result(value = "1.00"), range))
        assertEquals("H", classifyLabResultFlag(result(value = "1.000000000000000000001"), range))

        val exclusiveUpper = LabReferenceRange(
            maximum = BigDecimal("5.0"),
            maximumInclusive = false,
        )
        assertEquals("H", classifyLabResultFlag(result(value = "5.0"), exclusiveUpper))
    }

    @Test
    fun classificationLeavesQualitativeAndInequalityValuesUnclassified() {
        val range = LabReferenceRange(minimum = BigDecimal("0"), maximum = BigDecimal("10"))

        assertNull(classifyLabResultFlag(result(value = "(-)"), range))
        assertNull(classifyLabResultFlag(result(value = "<= 5.0"), range))
        assertNull(classifyLabResultFlag(result(value = ""), range))
        assertNull(classifyLabResultFlag(result(value = "NaN"), range))
        assertNull(classifyLabResultFlag(result(value = "Infinity"), range))
    }

    @Test
    fun displayTextPreservesOpenAndExclusiveBoundSemantics() {
        assertEquals(
            "3.6〜7.0",
            LabReferenceRange(BigDecimal("3.6"), BigDecimal("7.0")).displayText,
        )
        assertEquals(
            "0.1超〜1未満",
            LabReferenceRange(
                minimum = BigDecimal("0.1"),
                maximum = BigDecimal("1"),
                minimumInclusive = false,
                maximumInclusive = false,
            ).displayText,
        )
        assertEquals(
            "79以下",
            LabReferenceRange(maximum = BigDecimal("79")).displayText,
        )
        assertEquals(
            "18.0以上",
            LabReferenceRange(minimum = BigDecimal("18.0")).displayText,
        )
    }

    @Test
    fun applyRecalculatesAllMonthsAndPreservesUnsafeValues() {
        val range = LabReferenceRange(minimum = BigDecimal("3.6"), maximum = BigDecimal("7.0"))
        val records = listOf(
            result(item = "血糖", value = "3.0", referenceRange = "old", flag = "H"),
            result(item = "血糖", value = "7.0", referenceRange = "old", flag = "L"),
            result(item = "血糖", value = "8.0", referenceRange = "old", flag = "L"),
            result(item = "血糖", value = "(-)", referenceRange = "old", flag = "H"),
            result(item = "無効", value = "4.0", referenceRange = "old", flag = "H"),
            result(item = "無効集合", value = "4.0", referenceRange = "old", flag = "H"),
            result(item = "未設定", value = "4.0", referenceRange = "old", flag = "L"),
        )

        val applied = applyLabReferenceRanges(
            records = records,
            effectiveRanges = mapOf("血糖" to range, "無効" to null, "無効集合" to range),
            disabledItems = setOf("無効集合"),
        )

        assertEquals(listOf("L", "", "H", "H", "", "", "L"), applied.map { it.flag })
        assertEquals(List(7) { "old" }, applied.map { it.referenceRange })
    }

    private fun result(
        item: String = "試験項目",
        value: String = "1.0",
        referenceRange: String = "",
        flag: String = "",
    ): LabResult = LabResult(
        targetDate = LocalDate.of(2026, 10, 1),
        itemName = item,
        value = value,
        referenceRange = referenceRange,
        flag = flag,
    )
}
