package com.goenc.healthsheetsync.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goenc.healthsheetsync.R
import com.goenc.healthsheetsync.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

private val SheetInk = Color(0xFF182B3A)
private val SheetMuted = Color(0xFF667785)
private val SheetLine = Color(0xFFD6DEE3)
private val SheetBackground = Color(0xFFF1F4F6)
private val SheetAccent = Color(0xFF226B67)

private data class PlanChoice(val board: PfcBoard, val plan: PfcPlan)
private data class PlanDetail(
    val title: String, val nutrition: PfcNutrition, val imageId: String?,
    val text: String = "", val lines: List<String> = emptyList(),
)

@Composable
fun PfcBoardScreen(state: PfcBoardState, onRefresh: () -> Unit, onBack: () -> Unit) {
    val choices = remember(state.boards) {
        state.boards.flatMap { board -> board.plans.map { PlanChoice(board, it) } }.distinctBy { it.plan.id }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val choice = choices.firstOrNull { it.plan.id == selectedId }
        ?: choices.firstOrNull { it.plan.id == state.boards.firstOrNull()?.baselineId }
        ?: choices.firstOrNull()
    var detail by remember(choice?.plan?.id) { mutableStateOf<PlanDetail?>(null) }
    val tabs = rememberLazyListState()
    LaunchedEffect(choice?.plan?.id) {
        val index = choices.indexOf(choice)
        if (index >= 0) tabs.animateScrollToItem(index)
    }
    Column(Modifier.fillMaxSize().background(SheetBackground)) {
        Row(Modifier.fillMaxWidth().background(Color.White), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back_24), "戻る", tint = SheetInk, modifier = Modifier.size(20.dp))
            }
            LazyRow(state = tabs, modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                items(choices, key = { it.plan.id }) { candidate ->
                    val active = candidate.plan.id == choice?.plan?.id
                    Column(Modifier.width(IntrinsicSize.Max).semantics { selected = active; role = Role.Tab }
                        .clickable { selectedId = candidate.plan.id }.padding(horizontal = 12.dp)) {
                        Text(candidate.plan.name, color = if (active) SheetInk else SheetMuted,
                            fontSize = 15.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp), maxLines = 1)
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) SheetAccent else Color.Transparent))
                    }
                }
            }
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(painterResource(R.drawable.ic_refresh_24), "更新", tint = if (state.loading) SheetLine else SheetInk,
                    modifier = Modifier.size(20.dp))
            }
        }
        HorizontalDivider(color = SheetLine)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = SheetAccent)
        val showStatus = state.loading || state.status.startsWith("取得失敗") || state.status.contains("未同期画像")
        if (showStatus) Text(state.status.replace("比較ボード", "データ"), fontSize = 11.sp, lineHeight = 15.sp,
            color = if (state.status.startsWith("取得失敗")) Color(0xFFAC3D35) else SheetMuted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp))
        if (choice == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(if (state.loading) "保存済みのプランを取得中" else "保存済みのプランがない。右上の更新から取得。",
                    color = SheetMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        } else {
            ScrollPlanSheet(choice.plan, choice.board.mode, state.images,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
                onDetail = { detail = it })
        }
    }
    detail?.let { selected ->
        AlertDialog(onDismissRequest = { detail = null }, containerColor = Color.White,
            title = { Text(selected.title, color = SheetInk, fontSize = 18.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selected.imageId != null) FoodImage(selected.imageId, state.images, Modifier.fillMaxWidth().height(160.dp))
                    if (selected.text.isNotBlank()) Text(selected.text, color = SheetInk)
                    val nutrition = selected.nutrition
                    Text("${nutrition.kcal.number(0)} kcal  P ${nutrition.p.number(1)}g  " +
                        "F ${nutrition.f.number(1)}g  C ${nutrition.c.number(1)}g", color = SheetInk)
                    selected.lines.forEach { Text(it, color = SheetMuted, fontSize = 13.sp) }
                }
            }, confirmButton = { TextButton(onClick = { detail = null }) { Text("閉じる") } })
    }
}

/** PC印刷の左右配置を保ち、縮小せず縦スクロールで読む。 */
@Composable
private fun ScrollPlanSheet(plan: PfcPlan, mode: Int, images: Map<String, File>, modifier: Modifier,
                            onDetail: (PlanDetail) -> Unit) {
    key(plan.id) {
        Column(modifier.verticalScroll(rememberScrollState())) {
            PlanPaper(plan, mode, images, onDetail)
        }
    }
}

@Composable
private fun PlanPaper(plan: PfcPlan, mode: Int, images: Map<String, File>, onDetail: (PlanDetail) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(6.dp))
        .border(1.dp, SheetLine, RoundedCornerShape(6.dp)).padding(7.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable {
            onDetail(PlanDetail(plan.name, plan.nutrition, plan.imageId, plan.memo, listOfNotNull(plan.date)))
        }.padding(horizontal = 3.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("1日合計", color = SheetMuted, fontSize = 11.sp, lineHeight = 15.sp)
                Text("${plan.nutrition.kcal.number(0)} kcal", color = SheetInk, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 27.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("P ${plan.nutrition.p.number(1)}  /  F ${plan.nutrition.f.number(1)}  /  C ${plan.nutrition.c.number(1)} g",
                    color = SheetInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                Text(when (mode) { 1 -> "HIGH"; 2 -> "LOW"; else -> "Standard" }, color = SheetMuted, fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = SheetLine)
        BoxWithConstraints {
            if (maxWidth >= 600.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 2, 3).forEach { slot ->
                            MealPrintCard(slot, plan.meals.firstOrNull { it.slot == slot }, images, onDetail)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 4, 5).forEach { slot ->
                            val meal = plan.meals.firstOrNull { it.slot == slot }
                            if (meal != null && meal.items.any { it.sourceEnabled }) MealPrintCard(slot, meal, images, onDetail)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2, 4, 3, 5).forEach { slot ->
                        val meal = plan.meals.firstOrNull { it.slot == slot }
                        if (slot in listOf(0, 2, 3) || meal?.items?.any { it.sourceEnabled } == true) {
                            MealPrintCard(slot, meal, images, onDetail)
                        }
                    }
                }
            }
        }
        plan.meals.firstOrNull { it.slot == 9 && it.items.any { food -> food.sourceEnabled } }?.let {
            MealPrintCard(9, it, images, onDetail)
        }
    }
}

@Composable
private fun MealPrintCard(slot: Int, meal: PfcMeal?, images: Map<String, File>, onDetail: (PlanDetail) -> Unit) {
    val slotName = when (slot) { 0 -> "朝食"; 2 -> "昼食"; 3 -> "夕食"; 9 -> "食前ドリンク";
        1 -> "午前中間食"; 4 -> "午後間食"; else -> "フリー間食" }
    val mainItems = meal?.items?.filter { it.enabled }.orEmpty()
    val candidates = meal?.items?.filter { it.isMainDishCandidate && it.sourceEnabled && !it.enabled }.orEmpty()
    Column(Modifier.fillMaxWidth().border(0.7.dp, SheetLine, RoundedCornerShape(4.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Column(Modifier.fillMaxWidth().clickable(enabled = meal != null) {
            meal?.let { onDetail(PlanDetail(it.name, it.nutrition, it.imageId, it.memo,
                it.items.map { food -> "${food.name}  ${food.amount}" })) }
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (slot == 9) "食前ドリンク · 毎食前1回分" else meal?.name?.takeIf { it.isNotBlank() } ?: slotName,
                    color = SheetAccent, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                meal?.let {
                    PfcGraph(it.nutrition, Modifier.weight(1.5f))
                    Text("${it.nutrition.kcal.number(0)} kcal", fontSize = 13.sp, lineHeight = 18.sp,
                        color = SheetInk, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (mainItems.isEmpty()) Text("—", fontSize = 11.sp, color = SheetLine)
        else FoodGrid(mainItems, images, onDetail)
        if (candidates.isNotEmpty()) {
            HorizontalDivider(color = SheetLine)
            Text("主菜候補", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SheetMuted)
            FoodGrid(candidates, images, onDetail)
        }
    }
}

/** Bar widths show the energy ratio (P/C: 4 kcal/g, F: 9 kcal/g); labels show grams. */
@Composable
private fun PfcGraph(nutrition: PfcNutrition, modifier: Modifier = Modifier) {
    val values = listOf(nutrition.p, nutrition.f, nutrition.c)
    val energy = values.mapIndexed { index, value -> value.toFloat().coerceAtLeast(0f) * if (index == 1) 9f else 4f }
    val colors = listOf(SheetAccent, Color(0xFFC28A36), Color(0xFF6286B1))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            values.forEachIndexed { index, value ->
                Text("${listOf("P", "F", "C")[index]}${value.number(1)}", color = colors[index],
                    fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(SheetLine)
            .semantics { contentDescription = "PFCエネルギー比率" }) {
            energy.forEachIndexed { index, amount ->
                if (amount > 0f) Box(Modifier.weight(amount).fillMaxHeight().background(colors[index]))
            }
        }
    }
}

@Composable
private fun FoodGrid(items: List<PfcItem>, images: Map<String, File>, onDetail: (PlanDetail) -> Unit) {
    BoxWithConstraints {
        val columns = when { maxWidth >= 290.dp -> 4; maxWidth >= 210.dp -> 3; else -> 2 }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { food ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(3.dp)).clickable {
                            onDetail(PlanDetail(food.name, food.nutrition, food.imageId, food.amount, food.details))
                        }.semantics { contentDescription = food.name }, horizontalAlignment = Alignment.CenterHorizontally) {
                            val frame = if (food.enabled && food.isMainDishCandidate) SheetAccent else SheetLine
                            FoodImage(food.imageId, images, Modifier.size(40.dp).border(0.7.dp, frame, RoundedCornerShape(3.dp)))
                            Text(food.amount, color = SheetInk, fontSize = 12.sp, lineHeight = 16.sp,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FoodImage(id: String?, files: Map<String, File>, modifier: Modifier) {
    val file = id?.let { files[it.lowercase()] }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file, file?.lastModified()) {
        value = withContext(Dispatchers.IO) {
            if (file == null) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFF5F7F8)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, "食品画像", Modifier.fillMaxSize().padding(1.dp), contentScale = ContentScale.Fit) }
            ?: Text(if (id == null) "—" else "画像なし", color = SheetMuted, fontSize = 10.sp)
    }
}

private fun BigDecimal.number(scale: Int): String = setScale(scale, RoundingMode.HALF_UP).toPlainString()
