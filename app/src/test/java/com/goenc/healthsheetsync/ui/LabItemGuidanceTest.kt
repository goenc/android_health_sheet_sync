package com.goenc.healthsheetsync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabItemGuidanceTest {
    @Test
    fun clinicalCriteriaAreNotShownForDifferentOrMissingUnits() {
        assertTrue(labGuidanceFor("血糖(空腹時)", "mg/dL").clinicalGuide.contains("126 mg/dL"))
        listOf("mmol/L", "").forEach { unit ->
            val guide = labGuidanceFor("血糖(空腹時)", unit)
            assertFalse(guide.clinicalGuide.contains("126"))
            assertTrue(guide.clinicalGuide.contains("未確認"))
        }
        assertFalse(labGuidanceFor("アルブミン/Cre補正", "μg/mL").clinicalGuide.contains("300"))
        assertTrue(labGuidanceFor("アルブミン/Cre補正", "mg/g.cre").clinicalGuide.contains("300 mg/g Cr"))
        assertTrue(labGuidanceFor("推算GFRcreat", "mL/min/1.73 m²").clinicalGuide.contains("3か月以上"))
    }

    @Test
    fun a1cCriterionRetainsItsDiagnosticMeaningAndRequestedBoundary() {
        val guide = labGuidanceFor("HbA1c(NGSP)", "%")
        assertTrue(guide.clinicalGuide.contains("6.5％以上"))
        assertTrue(guide.clinicalGuide.contains("HbA1cだけで確定せず"))
        assertTrue(guide.clinicalGuide.contains("救急受診の境界ではない"))
        assertFalse(guide.clinicalGuide.contains("7.0"))
    }

    @Test
    fun unknownItemsAndQualitativeTestsDoNotBorrowNumericCriteria() {
        val unknown = labGuidanceFor("新しい検査", "mg/dL")
        assertTrue(unknown.clinicalGuide.contains("未確認"))
        assertTrue(unknown.sources.isEmpty())
        assertTrue(labGuidanceFor("糖定性-尿", "").clinicalGuide.contains("一律の危険境界はない"))
        assertFalse(labGuidanceFor("糖定性-尿", "").clinicalGuide.contains("126"))
        assertTrue(labGuidanceFor("白血球数(WBC)", "/μL").clinicalGuide.contains("好中球数は別の指標"))
        assertTrue(labGuidanceFor("クレアチニン(CREA)", "mg/dL").clinicalGuide.contains("48時間以内"))
    }

    @Test
    fun currentlySupportedItemsHaveExplanationsAndTraceableSources() {
        val expectedNames = setOf("尿酸(UA)", "尿素窒素(BUN)", "LDLコレステロール", "クレアチニン(CREA)",
            "ALB/BCP改良", "ナトリウム(Na)", "カリウム(K)", "クロール(Cl)", "中性脂肪(TG)", "HDLコレステロール",
            "総ビリルビン", "直接ビリルビン", "AST(GOT)", "ALT(GPT)", "γ-GT(γ-GTP)", "CK", "アミラーゼ(AMY)",
            "血糖(空腹時)", "クレアチニン-尿", "アルブミン/Cre補正", "アルブミン定量値", "ALP/IFCC", "HbA1c(NGSP)",
            "C-ペプチド 前", "抗GAD抗体/EIA", "白血球数(WBC)", "赤血球数(RBC)", "血色素量(Hb)", "ヘマトクリット(Ht)",
            "MCV", "MCH", "MCHC", "血小板数", "蛋白定性-尿", "糖定性-尿", "ウロビリノーゲン定性-尿", "ビリルビン定性-尿",
            "比重-尿", "反応pH-尿", "ケトン体定性-尿", "潜血反応-尿", "推算GFRcreat", "HbF")
        assertEquals(expectedNames, labItemGuidance.keys)
        labItemGuidance.values.forEach { guide ->
            assertTrue(guide.meaning.isNotBlank() && guide.purpose.isNotBlank() && guide.clinicalGuide.isNotBlank())
            assertTrue(guide.sources.isNotEmpty())
            assertTrue(guide.sources.all { it.title.isNotBlank() && it.url.startsWith("https://") })
        }
    }
}
