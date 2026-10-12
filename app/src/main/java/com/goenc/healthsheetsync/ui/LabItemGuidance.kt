package com.goenc.healthsheetsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class LabGuidanceSource(val title: String, val url: String)

internal data class LabItemGuidance(
    val meaning: String,
    val purpose: String,
    val clinicalGuide: String,
    val units: Set<String>,
    val sources: List<LabGuidanceSource>,
)

private val diabetesDiagnosis = LabGuidanceSource("日本糖尿病学会・診断指針2024", "https://www.jds.or.jp/uploads/files/publications/gl2024/01.pdf")
private val diabetesSafety = LabGuidanceSource("ADA・血糖管理2026", "https://doi.org/10.2337/dc26-s006")
private val lipids = LabGuidanceSource("日本動脈硬化学会・脂質異常症Q&A", "https://www.j-athero.org/jp/publications/si_qanda/")
private val lipidTargets = LabGuidanceSource("日本動脈硬化学会・管理目標2025", "https://www.j-athero.org/chart2025/houkatsu_chart2025.pdf")
private val diabetesLipids = LabGuidanceSource("日本糖尿病学会・脂質異常症2024", "https://www.jds.or.jp/uploads/files/publications/gl2024/15_1.pdf")
private val triglycerides = LabGuidanceSource("Endocrine Society・脂質管理2020", "https://www.endocrine.org/clinical-practice-guidelines/lipid-management-guideline")
private val kidney = LabGuidanceSource("National Kidney Foundation・腎機能の評価", "https://www.kidney.org/quick-reference-guide-kidney-disease-screening")
private val kidneyNumbers = LabGuidanceSource("National Kidney Foundation・検査の意味", "https://www.kidney.org/kidney-topics/understanding-your-lab-values-and-other-ckd-health-numbers")
private val acuteKidney = LabGuidanceSource("KDIGO・急性腎障害2012", "https://kdigo.org/wp-content/uploads/2016/10/KDIGO-2012-AKI-Guideline-English.pdf")
private val uricAcid = LabGuidanceSource("厚生労働省・高尿酸血症の治療検討2019", "https://www.mhlw.go.jp/content/11120000/000522020.pdf")
private val bilirubin = LabGuidanceSource("MedlinePlus・ビリルビン検査", "https://medlineplus.gov/lab-tests/bilirubin-blood-test/")
private val jaundice = LabGuidanceSource("MSD Manual・成人の黄疸", "https://www.msdmanuals.com/professional/hepatic-and-biliary-disorders/approach-to-the-patient-with-liver-disease/jaundice")
private val erythrocytosis = LabGuidanceSource("CMAJ・赤血球増加の評価2020", "https://doi.org/10.1503/cmaj.191587")
private val liver = LabGuidanceSource("MedlinePlus・肝臓の検査", "https://medlineplus.gov/lab-tests/liver-function-tests/")
private val bloodIndices = LabGuidanceSource("MedlinePlus・赤血球指数", "https://medlineplus.gov/ency/article/003648.htm")
private val urine = LabGuidanceSource("MedlinePlus・尿検査", "https://medlineplus.gov/ency/article/003579.htm")
private val albuminuria = LabGuidanceSource("KDIGO・慢性腎臓病2024", "https://kdigo.org/wp-content/uploads/2024/03/KDIGO-2024-CKD-Guideline.pdf")
private const val NO_SINGLE_CUTOFF = "この項目だけで診断・治療・緊急性を決める一律の数値はない。変化の速さ、症状、関連する検査と合わせて判断する。"

private fun guide(meaning: String, purpose: String, clinicalGuide: String, unit: String, vararg sources: LabGuidanceSource) =
    LabItemGuidance(meaning, purpose, clinicalGuide, setOf(unit), sources.toList())

// Clinical decision criteria are educational text, not replacements for each laboratory's reference range.
// Sources and scope were checked on 2026-10-12. No patient values are stored here.
internal val labItemGuidance: Map<String, LabItemGuidance> = mapOf(
    "HbA1c(NGSP)" to guide(
        "過去およそ2〜3か月の血糖状態を反映する値。",
        "日々の血糖だけでは見えない、糖尿病管理の長期的な変化を見る。",
        "診断の目安：6.5％以上は糖尿病型。HbA1cだけで確定せず、血糖値や再検査などで確認する。治療中の目標値は個別に決めるため、6.5％は救急受診の境界ではない。",
        "%", diabetesDiagnosis, diabetesSafety),
    "血糖(空腹時)" to guide(
        "食事前の血液中のブドウ糖濃度。",
        "空腹時の高血糖と、薬・食事・運動による低血糖を確認する。",
        "診断の目安：空腹時126 mg/dL以上は糖尿病型（確認検査等が必要）。低血糖は70 mg/dL未満、54 mg/dL未満は直ちに対応が必要な低血糖。意識障害や飲み込めない状態では数値にかかわらず救急対応。高血糖の緊急性はケトン・脱水・症状も含めて判断する。",
        "mg/dL", diabetesDiagnosis, diabetesSafety),
    "LDLコレステロール" to guide(
        "動脈硬化に関係する、LDL粒子に含まれるコレステロール。",
        "心筋梗塞・脳梗塞の予防のため、治療目標に対する推移を見る。",
        "治療の目安：糖尿病の一次予防では120 mg/dL未満が基本。末梢動脈疾患・細小血管症の合併、または喫煙があれば100未満、冠動脈疾患・アテローム血栓性脳梗塞の既往がある二次予防では70未満を目指す。個別の目標を主治医と確認する。",
        "mg/dL", diabetesLipids, lipids),
    "HDLコレステロール" to guide(
        "余分なコレステロールの回収に関係するHDL中のコレステロール。",
        "LDL・中性脂肪と一緒に、血管のリスクを評価する。",
        "リスク評価の目安：40 mg/dL未満は低HDLコレステロール血症。単独の救急値ではなく、HDLを上げることだけで治療方針を決めない。",
        "mg/dL", lipids, lipidTargets),
    "中性脂肪(TG)" to guide(
        "血液中の脂肪の一種。食事、飲酒、血糖状態などで変動する。",
        "代謝状態と動脈硬化リスク、著しく高い場合は膵炎リスクを見る。",
        "治療の目安：空腹時500 mg/dL超では膵炎予防のため薬物治療を検討する。1,000 mg/dL超では血糖管理・食事等も特に重要。低値側には一律の危険値はなく、栄養状態や体重変化も確認する。",
        "mg/dL", triglycerides),
    "クレアチニン(CREA)" to guide(
        "筋肉で生じ、腎臓から排泄される老廃物。筋肉量にも影響される。",
        "eGFRと合わせて腎機能を見る。急な上昇にも注意する。",
        "急性腎障害の目安：48時間以内に0.3 mg/dL以上上昇、または7日以内に元の1.5倍以上に上昇。絶対値だけの一律の危険値はない。数か月間隔の検査同士にはこの時間条件を適用できない。",
        "mg/dL", acuteKidney, kidneyNumbers),
    "推算GFRcreat" to guide(
        "クレアチニン等から推定する、腎臓のろ過能力。",
        "糖尿病に伴う腎機能低下の有無と進み方を見る。",
        "腎機能分類：標準化eGFRが60 mL/min/1.73 m²未満で3か月以上続けばCKDの診断条件の一つ。30未満は高度低下、15未満は腎不全の分類。検査票のmL/min表記が体表面積補正済みか確認する。単回値だけで透析・救急を決めない。",
        "mL/min", kidney).copy(units = setOf("mL/min", "mL/min/1.73 m²", "mL/min/1.73m²", "mL/min/1.73m2")),
    "アルブミン/Cre補正" to guide(
        "尿アルブミンを尿クレアチニンで補正した比。尿の濃さの影響を減らす。",
        "eGFRが保たれている時期も含め、糖尿病による腎臓の傷みを調べる。",
        "腎障害の目安：30〜299 mg/g Crは中等度、300 mg/g Cr以上は高度のアルブミン尿。運動・発熱などによる一時的上昇を除き、再検査で持続を確認する。単独の救急値ではない。",
        "mg/g.cre", kidney, albuminuria),
    "尿酸(UA)" to guide(
        "プリン体の分解で生じる老廃物。腎臓等から排泄される。",
        "痛風や尿路結石のリスク、尿酸を下げる治療の必要性を見る。",
        "治療を相談する目安：無症状で合併症がなければ9.0 mg/dL以上、糖尿病・腎障害・尿路結石・高血圧等があれば8.0 mg/dL以上で薬物治療を検討する。生活習慣改善の効果や痛風発作の有無も含めて判断する。",
        "mg/dL", uricAcid),
    "総ビリルビン" to guide(
        "赤血球の分解で生じる色素の総量。肝臓で処理され胆汁に排泄される。",
        "直接ビリルビンや肝酵素と合わせ、黄疸・肝胆道疾患・溶血等を見る。",
        "臨床所見の目安：およそ2〜3 mg/dLで黄疸が見えることがある。これは救急の境界ではない。危険度を決める一律の値はなく、腹痛・発熱・意識変化などと合わせて判断する。",
        "mg/dL", bilirubin, jaundice),
    "直接ビリルビン" to guide(
        "肝臓で処理され、水に溶ける形になったビリルビン。",
        "総ビリルビンに対する割合やALP・γ-GTと合わせ、胆汁の流れや肝障害を見る。",
        "単独の一律の危険値はない。上昇が続く場合は総ビリルビンの分画、肝酵素、症状を確認する。総ビリルビンの黄疸の目安を直接値へ流用しない。",
        "mg/dL", bilirubin, jaundice),
    "ヘマトクリット(Ht)" to guide(
        "血液全体に占める赤血球の体積の割合。脱水でも高くなる。",
        "Hbと合わせて貧血・赤血球増加・血液の濃縮の有無を見る。",
        "追加評価の目安：成人男性49％超、女性48％超は真性多血症の診断基準の一要素。これだけで診断せず、持続性・脱水・喫煙等の原因も調べる。値だけで緊急性や治療を決めない。",
        "%", erythrocytosis),
    "尿素窒素(BUN)" to guide("たんぱく質の代謝で生じる尿素に含まれる窒素。", "腎機能だけでなく脱水・食事・消化管出血等の影響も、クレアチニンと比較する。", NO_SINGLE_CUTOFF, "mg/dL", kidneyNumbers),
    "ALB/BCP改良" to guide("血液中の主要なたんぱく質、アルブミンの濃度。", "肝臓での合成、炎症、栄養状態、腎臓等からのたんぱく喪失を総合して見る。", "低値だけで栄養不良や肝不全と断定できない。一律の救急値はなく、炎症・尿蛋白・肝臓の検査等も確認する。", "g/dL", liver, kidneyNumbers),
    "ナトリウム(Na)" to guide("体内の水分バランスに関係する電解質。", "水分と塩分のバランス、薬や腎臓・ホルモンの影響を見る。", "重症度分類の目安：125 mEq/L未満は高度の低ナトリウム血症。意識障害・けいれんは値にかかわらず救急評価。発症速度と血糖などでも解釈が変わり、自己判断で急速に補正しない。", "mEq/L", LabGuidanceSource("欧州合同・低Naガイドライン2014", "https://academic.oup.com/ejendo/article/170/3/G1/6668028")),
    "カリウム(K)" to guide("心臓や筋肉の電気的な働きに必要な電解質。", "不整脈につながる増減と、腎機能・薬剤の影響を見る。", "急いで評価する目安：6.5 mEq/L以上は重症高カリウム血症。およそ2.5 mEq/L未満も不整脈・筋麻痺等のリスクがある。採血時の溶血による偽高値も確認するが、受診を自己判断で遅らせない。", "mEq/L", LabGuidanceSource("UK Kidney Association・高K管理2026", "https://www.ukkidney.org/sites/default/files/documents/FINAL%20VERSION%20-%20UKKA%20CLINICAL%20PRACTICE%20GUIDELINE%20-%20MANAGEMENT%20OF%20HYPERKALAEMIA%20IN%20ADULTS%20-%20UPDATED%20JULY%202026_0.pdf"), LabGuidanceSource("NIH・カリウム", "https://ods.od.nih.gov/factsheets/Potassium-HealthProfessional/")),
    "クロール(Cl)" to guide("体液中の主要な陰イオンである電解質。", "Naや他の電解質と合わせ、水分・酸塩基バランスを見る。", NO_SINGLE_CUTOFF, "mEq/L", LabGuidanceSource("MedlinePlus・クロール検査", "https://medlineplus.gov/lab-tests/chloride-blood-test/")),
    "AST(GOT)" to guide("肝臓や筋肉などの細胞に含まれる酵素。", "ALT・CK等との組み合わせで、肝臓と筋肉の障害を見分ける。", "数値だけで肝不全や緊急性は決められない。肝障害の重症度は凝固能・意識状態等でも評価する。筋肉由来の上昇もある。", "U/L", liver),
    "ALT(GPT)" to guide("主に肝細胞に含まれる酵素。", "脂肪肝・肝炎・薬剤等による肝細胞障害の推移を見る。", "数値だけで肝不全や緊急性は決められない。肝機能そのものとは異なり、凝固能・意識状態・ビリルビン等も確認する。", "U/L", liver),
    "γ-GT(γ-GTP)" to guide("肝臓や胆道に関係する酵素。飲酒や薬剤でも上がる。", "ALPやビリルビンと合わせ、胆汁の流れや肝胆道への影響を見る。", NO_SINGLE_CUTOFF, "U/L", liver),
    "ALP/IFCC" to guide("胆道や骨などに由来する酵素。IFCC法による値。", "γ-GT等と合わせて胆道由来か骨由来かを評価する。", "一律の危険値はない。測定法によって値が異なるため、旧JSCC法の数値をそのまま比較しない。", "U/L", liver),
    "CK" to guide("筋肉の細胞に含まれる酵素。運動や筋障害で上昇する。", "筋障害の程度を、症状・腎機能・カリウムと合わせて見る。", "診断補助の目安：CKが基準上限の5倍超、または1,000 U/L超は、症状等を伴う状況で横紋筋融解症を疑う目安。これだけで確定や緊急度は決めず、強い筋痛・褐色尿等も確認する。", "U/L", LabGuidanceSource("AAST・横紋筋融解症の合意文書2022", "https://pmc.ncbi.nlm.nih.gov/articles/PMC8804685/")),
    "アミラーゼ(AMY)" to guide("膵臓や唾液腺で作られる消化酵素。", "腹痛やリパーゼ、画像等と合わせて膵炎などを評価する。", "診断補助の目安：基準上限の3倍超は急性膵炎の診断要素の一つ。典型的腹痛・酵素上昇・画像の3要素中2つが必要で、この値だけでは診断しない。", "U/L", LabGuidanceSource("ACG・急性膵炎ガイドライン2024", "https://pubmed.ncbi.nlm.nih.gov/38857482/")),
    "クレアチニン-尿" to guide("尿中に排泄されたクレアチニンの濃度。", "尿アルブミン等を尿の濃さで補正するために使う。", "尿濃度や筋肉量で変動する。単独で腎機能や危険性を判定する数値はなく、血清クレアチニンと同じ基準を使わない。", "g/L", albuminuria),
    "アルブミン定量値" to guide("尿中アルブミンの濃度。", "腎臓からのアルブミン漏出を調べ、尿クレアチニンで補正した比と合わせる。", "尿の濃さに影響されるため、濃度だけの一律の危険値はない。30・300 mg/g Crという比の基準を、μg/mLの濃度へ直接適用しない。", "μg/mL", albuminuria),
    "C-ペプチド 前" to guide("自分の膵臓からインスリンが分泌される際に生じる物質。", "採血時の血糖・食事条件・腎機能と合わせて、自分のインスリン分泌を調べる。", "一律の危険値はない。低値や高値の意味は同時血糖、採血条件、測定法等で変わり、単独で糖尿病の型や治療を決めない。", "ng/mL", LabGuidanceSource("MedlinePlus・Cペプチド", "https://medlineplus.gov/lab-tests/c-peptide-test/")),
    "抗GAD抗体/EIA" to guide("膵島への自己免疫に関係する抗体。", "自己免疫性の糖尿病を評価するため、インスリン分泌等と一緒に確認する。", "陽性の境界は測定法・検査室で異なる。検査票の判定基準を使う。陽性・陰性や力価だけで緊急性や治療を決めない。", "U/mL", diabetesDiagnosis),
    "白血球数(WBC)" to guide("感染への防御等を担う白血球の総数。", "炎症・感染、薬剤や骨髄の影響を白血球分画と合わせて見る。", "総白血球数だけの一律の危険値はない。感染リスクに重要な好中球数は別の指標であり、総白血球数と同じ閾値で判定しない。", "/μL", LabGuidanceSource("MedlinePlus・白血球数", "https://medlineplus.gov/lab-tests/white-blood-count-wbc/")),
    "赤血球数(RBC)" to guide("酸素を運ぶ赤血球の数。", "Hb・Ht・赤血球指数と合わせ、貧血や赤血球増加の原因を見る。", "数だけの一律の危険値はない。赤血球の大きさやHb濃度、脱水の有無でも解釈が変わる。", "万/μL", bloodIndices, erythrocytosis),
    "血色素量(Hb)" to guide("酸素を運ぶヘモグロビンの血液中濃度。", "貧血の有無や程度を確認し、Ht・MCV等で原因を評価する。", "成人15〜65歳の貧血の目安：男性13.0 g/dL未満、非妊娠女性12.0未満。8.0未満はWHOの重症貧血分類。輸血や救急の単独基準ではなく、出血・症状・持病等で対応が変わる。", "g/dL", LabGuidanceSource("WHO・貧血のHb基準2024", "https://www.who.int/publications/i/item/9789240088542")),
    "MCV" to guide("赤血球1個の平均的な大きさ。", "貧血を小球性・正球性・大球性に分け、鉄やビタミン不足等の原因を絞る。", "成人の分類目安：80 fL未満は小球性、100 fL超は大球性。これは貧血の原因を調べる分類で、救急の境界ではない。", "fL", bloodIndices),
    "MCH" to guide("赤血球1個あたりの平均ヘモグロビン量。", "MCV・MCHC・Hbと合わせ、貧血の性質を調べる。", NO_SINGLE_CUTOFF, "pg", bloodIndices),
    "MCHC" to guide("赤血球内の平均ヘモグロビン濃度。", "赤血球の色素の薄さ等を、Hb・MCVと合わせて確認する。", NO_SINGLE_CUTOFF, "%", bloodIndices).copy(units = setOf("%", "g/dL", "g/L")),
    "血小板数" to guide("出血を止める働きに関係する血小板の数。", "出血リスクや、薬・免疫・骨髄等の影響を見る。", "重症度の目安：2万/μL未満は重症の血小板減少。原因や出血の有無で対応が変わり、一律の輸血基準ではない。出血が続く場合は数値だけで待たない。", "万/μL", LabGuidanceSource("NHLBI・血小板異常の診断", "https://www.nhlbi.nih.gov/health/platelet-disorders/diagnosis")),
    "蛋白定性-尿" to guide("尿にたんぱく質が出ているかを見る試験紙検査。", "腎障害の手がかりを調べ、陽性なら尿アルブミン/Cre比等で確認する。", "定性の＋だけでは量や危険度を確定できない。運動・発熱・尿の濃さで変わるため、持続性と定量検査を確認する。", "", urine, albuminuria),
    "糖定性-尿" to guide("尿にブドウ糖が出ているかを見る検査。", "血糖や使用薬と合わせて解釈する。血糖管理の主指標にはしない。", "尿糖の＋に一律の危険境界はない。SGLT2阻害薬では血糖が高くなくても陽性になる。糖尿病の判断は血糖・HbA1cで行う。", "", LabGuidanceSource("ADA・糖尿病の検査ガイドライン2023", "https://diabetesjournals.org/care/article/46/10/1740/153424/Guidelines-and-Recommendations-for-Laboratory")),
    "ウロビリノーゲン定性-尿" to guide("ビリルビン代謝で生じる物質が尿に出る程度。", "肝胆道疾患や溶血の補助情報を、他の検査と合わせて見る。", "定性の＋だけで診断・緊急性は決められない。尿ビリルビンや血液の肝胆道検査と合わせる。", "", LabGuidanceSource("MedlinePlus・尿ウロビリノーゲン", "https://medlineplus.gov/lab-tests/urobilinogen-in-urine/")),
    "ビリルビン定性-尿" to guide("尿にビリルビンが出ているかを見る検査。", "肝臓や胆道への影響を、血液のビリルビン等と合わせて確認する。", "通常は陰性。陽性なら原因を確認するが、定性検査だけの救急境界はない。", "", LabGuidanceSource("MedlinePlus・尿ビリルビン", "https://medlineplus.gov/lab-tests/bilirubin-in-urine/")),
    "比重-尿" to guide("尿の濃縮度を表す値。", "水分摂取・脱水・腎臓の濃縮能力や尿糖の影響を見る。", "一律の危険値はない。水分状態や尿糖等で変化するため、単独で脱水や腎障害と決めない。", "", LabGuidanceSource("MedlinePlus・尿比重", "https://medlineplus.gov/ency/article/003587.htm")),
    "反応pH-尿" to guide("尿がどの程度酸性・アルカリ性かを表す値。", "食事、尿路感染、結石、酸塩基バランス等の補助情報として見る。", "尿pHだけの一律の危険値はない。血液のpHとは別の検査であり、尿の値だけで全身の酸性化を判定しない。", "", LabGuidanceSource("MedlinePlus・尿pH", "https://medlineplus.gov/ency/article/003583.htm")),
    "ケトン体定性-尿" to guide("脂肪の利用で生じるケトン体を尿で調べる検査。", "絶食やインスリン不足等を、血糖・症状・血液の検査と合わせて確認する。", "尿の＋だけでは糖尿病性ケトアシドーシスと診断できない。尿試験紙は主な血中ケトンを十分反映しないため、嘔吐・腹痛・呼吸異常等があれば血糖が高くなくても急いで評価する。", "", LabGuidanceSource("ADA/EASD・高血糖緊急症の合意文書2024", "https://doi.org/10.2337/dci24-0032")),
    "潜血反応-尿" to guide("尿中の血液成分への反応を調べる検査。", "尿路・腎臓の病気等の手がかりを、尿沈渣などで確認する。", "定性の＋だけで危険度は決めない。赤血球以外のヘモグロビン・ミオグロビンにも反応するため、尿沈渣や症状を合わせる。", "", urine),
    "HbF" to guide("胎児期に多い型のヘモグロビン。成人では通常少量。", "ヘモグロビンの性質や造血の状態を、他の血液検査と合わせて見る。", "一律の緊急境界はない。年齢・遺伝的なヘモグロビンの違い・薬等で変化し、測定法やHbA1cとの関係も確認する。", "", LabGuidanceSource("American Society of Hematology・Hb分画", "https://www.hematology.org/education/trainees/fellows/hematopoiesis/2021/hemoglobin-electrophoresis-in-sickle-cell-disease")),
)

internal fun labGuidanceFor(itemName: String, unit: String): LabItemGuidance {
    val guidance = labItemGuidance[itemName] ?: return LabItemGuidance(
        "この項目の説明はまだ登録されていない。", "検査票の正式な項目名と測定法を確認する。",
        "臨床判断に使える数値の目安は未確認。検査票の基準範囲だけで危険性を断定しない。", emptySet(), emptyList())
    return if (unit in guidance.units) guidance else guidance.copy(
        clinicalGuide = "この単位で使える臨床判断の目安は未確認。測定単位・測定法を確認してから比較する。")
}

@Composable
internal fun LabItemGuidancePanel(itemName: String, unit: String) {
    val guidance = labGuidanceFor(itemName, unit)
    val uriHandler = LocalUriHandler.current
    var showExplanation by rememberSaveable(itemName, unit) { mutableStateOf(false) }
    var showSources by rememberSaveable(itemName, unit) { mutableStateOf(false) }
    var sourceError by rememberSaveable(itemName, unit) { mutableStateOf(false) }
    TextButton(onClick = {
        showSources = false
        sourceError = false
        showExplanation = true
    }) { Text("説明") }
    if (showExplanation) {
        AlertDialog(
            onDismissRequest = { showExplanation = false },
            title = { Text("$itemName の説明") },
            confirmButton = {
                TextButton(onClick = { showExplanation = false }) { Text("閉じる") }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(guidance.meaning, style = MaterialTheme.typography.bodySmall)
                    Text("見る目的：${guidance.purpose}", style = MaterialTheme.typography.bodySmall)
                    Text("臨床判断の目安", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AppPrimary)
                    Text(guidance.clinicalGuide, style = MaterialTheme.typography.bodySmall)
                    if (guidance.sources.isNotEmpty()) {
                        TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "根拠を閉じる" else "根拠・確認日", style = MaterialTheme.typography.labelSmall) }
                        if (showSources) {
                            Text("資料確認：2026/10/12。診断基準・治療目標・救急の目安は意味が異なる。個別の治療目標は主治医と確認する。", style = MaterialTheme.typography.bodySmall, color = AppMutedBlue)
                            guidance.sources.forEach { source ->
                                TextButton(onClick = { sourceError = runCatching { uriHandler.openUri(source.url) }.isFailure }) {
                                    Text(source.title, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (sourceError) Text("出典を開けなかった。ブラウザの設定を確認する。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        )
    }
}
