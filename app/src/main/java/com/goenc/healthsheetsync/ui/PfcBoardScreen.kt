package com.goenc.healthsheetsync.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.goenc.healthsheetsync.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PfcBoardScreen(state: PfcBoardState, onRefresh: () -> Unit, onBack: () -> Unit) {
    var boardId by remember { mutableStateOf<String?>(null) }
    val board = state.boards.firstOrNull { it.id == boardId } ?: state.boards.firstOrNull()
    var planId by remember(board?.id) { mutableStateOf<String?>(null) }
    var baselineId by remember(board?.id) { mutableStateOf<String?>(null) }
    val baseline = board?.plans?.firstOrNull { it.id == (baselineId ?: board.baselineId) } ?: board?.plans?.firstOrNull()
    val plan = board?.plans?.firstOrNull { it.id == planId } ?: baseline
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("戻る") }
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("更新") }
        }
        Text("比較ボード · 閲覧専用", style = MaterialTheme.typography.titleLarge)
        Text(state.status, style = MaterialTheme.typography.bodySmall)
        state.updatedAt?.let { updated ->
            val display = remember(updated) {
                runCatching { java.time.Instant.parse(updated).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")) }.getOrDefault(updated)
            }
            Text("最終取得: $display", style = MaterialTheme.typography.bodySmall)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (board == null) {
            Text(if (state.loading) "初回の全履歴を取得・復元中。このままお待ちください。"
                else "表示できる比較ボードなし。PCで保存・同期後、「更新」を実行。", Modifier.padding(vertical = 20.dp))
            return@Column
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.boards.forEach { candidate ->
                FilterChip(selected = board.id == candidate.id, onClick = { boardId = candidate.id },
                    label = { Text(candidate.name) })
            }
        }
        Text("計算モード: ${when (board.mode) { 0 -> "Standard"; 1 -> "HIGH"; 2 -> "LOW"; else -> "未対応" }}",
            style = MaterialTheme.typography.bodySmall)
        if (baseline == null || plan == null) {
            Text("ボードに日次プランなし", Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            item {
                Text("比較差分 = 各プラン − 基準（${baseline.name}）", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    board.plans.forEach { candidate ->
                        Text("${candidate.name}: ${candidate.nutrition.summary}")
                        Text("差分: ${candidate.nutrition.difference(baseline.nutrition)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text("基準を選択", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    board.plans.forEach { candidate ->
                        FilterChip(selected = baseline.id == candidate.id, onClick = { baselineId = candidate.id },
                            label = { Text(candidate.name) })
                    }
                }
                Text("食事内容を表示", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    board.plans.forEach { candidate ->
                        FilterChip(selected = plan.id == candidate.id, onClick = { planId = candidate.id },
                            label = { Text(candidate.name) })
                    }
                }
                Text("${plan.name} ${plan.date.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                if (plan.memo.isNotBlank()) Text(plan.memo)
                PfcImage(plan.imageId, state.images)
                Text(plan.nutrition.summary)
            }
            items(plan.meals) { meal ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(meal.name, style = MaterialTheme.typography.titleMedium)
                        if (meal.memo.isNotBlank()) Text(meal.memo)
                        PfcImage(meal.imageId, state.images)
                        Text(meal.nutrition.summary)
                        val combined = nutritionForSlot(plan, meal.slot)
                        if (meal.slot in listOf(0, 2, 3)) Text("食前飲料込み: ${combined.summary}", style = MaterialTheme.typography.bodySmall)
                        Text("差分: ${combined.difference(nutritionForSlot(baseline, meal.slot), true)}",
                            style = MaterialTheme.typography.bodySmall)
                        meal.items.forEach { food ->
                            HorizontalDivider()
                            Text("${food.name}  ${food.amount}${if (food.enabled) "" else "（集計対象外）"}")
                            PfcImage(food.imageId, state.images)
                            Text(food.nutrition.summary, style = MaterialTheme.typography.bodySmall)
                            food.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun PfcImage(id: String?, files: Map<String, File>) {
    if (id.isNullOrBlank()) return
    val file = files[id.lowercase()]
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file, file?.lastModified()) {
        value = withContext(Dispatchers.IO) {
            if (file == null) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, contentDescription = "関連画像", modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
        contentScale = ContentScale.Fit) } ?: Text(if (file == null) "画像未取得" else "画像を読み込めない場合は更新してください",
        style = MaterialTheme.typography.bodySmall)
}

private fun nutritionForSlot(plan: PfcPlan, slot: Int): PfcNutrition {
    val zero = java.math.BigDecimal.ZERO
    val meal = plan.meals.firstOrNull { it.slot == slot }?.nutrition ?: PfcNutrition(zero, zero, zero, zero, zero)
    return if (slot in listOf(0, 2, 3)) plan.meals.firstOrNull { it.slot == 9 }?.let { meal + it.nutrition } ?: meal else meal
}
