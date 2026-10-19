package com.goenc.healthsheetsync.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class PfcBoardState(
    val boards: List<PfcBoard> = emptyList(),
    val images: Map<String, File> = emptyMap(),
    val loading: Boolean = false,
    val status: String = "「更新」でPCの比較ボードを取得",
    val updatedAt: String? = null,
)

internal class PfcBoardReadException(message: String) : Exception(message)

/** SpreadsheetUploaderと同じ標準HTTP方式。リモート操作はGETだけに限定。 */
internal class PfcBoardReader(context: Context) {
    private val directory = File(context.noBackupFilesDir, "pfc-viewer").apply { mkdirs() }
    private val cache = AtomicFile(File(directory, "snapshot.json"))

    suspend fun cached(): PfcBoardState = withContext(Dispatchers.IO) {
        if (!cache.baseFile.exists()) return@withContext PfcBoardState()
        runCatching {
            val saved = cache.openRead().bufferedReader().use { JSONObject(it.readText()) }
            require(saved.getString("datasetId") == PfcSyncSnapshot.DATASET_ID)
            val snapshot = PfcSyncSnapshot.fromJson(saved.getJSONObject("rows"))
            PfcBoardState(snapshot.boards(), imageFiles(snapshot), status = "端末内の取得済みデータ",
                updatedAt = saved.getString("updatedAt"))
        }.getOrElse { PfcBoardState(status = "端末内データを復元できません。「更新」で再取得") }
    }

    suspend fun read(token: String): PfcBoardState = withContext(Dispatchers.IO) {
        val inventory = listFiles(token)
        val batchFiles = inventory.filter { file ->
            file.getString("name").startsWith("pfc-sync-") &&
                file.optJSONObject("appProperties")?.optString("datasetId").equals(PfcSyncSnapshot.DATASET_ID, true)
        }.sortedWith(compareBy({ it.getJSONObject("appProperties").getString("deviceId") },
            { it.getJSONObject("appProperties").getString("deviceSequence").toLong() },
            { it.getJSONObject("appProperties").getString("batchId") }))
        // 空一覧は別OAuthプロジェクトでも起こる。既存キャッシュを空で上書きしない。
        if (batchFiles.isEmpty()) {
            val project = oauthProjectNumber(token)
            throw PfcBoardReadException("同期データなし。PCの同期、同じGoogleアカウント・OAuthプロジェクト・DatasetIdを確認" +
                (project?.let { "。Android OAuthプロジェクト番号: $it" } ?: ""))
        }
        val reducer = PfcSyncReducer()
        batchFiles.forEach { file ->
            currentCoroutineContext().ensureActive()
            val properties = file.getJSONObject("appProperties")
            val batch = JSONObject(download(token, file.getString("id"), 32 * 1024 * 1024).toString(Charsets.UTF_8))
            require(batch.getString("batchId").equals(properties.getString("batchId"), true) &&
                batch.getString("deviceId") == properties.getString("deviceId") &&
                batch.getLong("deviceSequence") == properties.getString("deviceSequence").toLong()) { "同期情報が不一致" }
            reducer.apply(batch)
        }
        val snapshot = reducer.snapshot()
        val boards = snapshot.boards() // 関連行・計算の検証も保存前に完了させる
        val imageInventory = inventory.filter { it.getString("name").startsWith("pfc-image-") }
            .filter { it.optJSONObject("appProperties")?.optString("datasetId").equals(PfcSyncSnapshot.DATASET_ID, true) }
            .associateBy { it.getJSONObject("appProperties").optString("contentHash").lowercase() }
        val referencedImages = boards.flatMap { it.plans }.flatMap { plan ->
            listOfNotNull(plan.imageId) + plan.meals.flatMap { meal ->
                listOfNotNull(meal.imageId) + meal.items.mapNotNull { it.imageId }
            }
        }.map { it.lowercase() }.toSet()
        var missingImages = referencedImages.count { snapshot.find("ImageAssetEntity", it) == null }
        snapshot.all("ImageAssetEntity").filter { it.getString("id").lowercase() in referencedImages }.forEach { image ->
            currentCoroutineContext().ensureActive()
            val hash = image.getString("sha256").lowercase()
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "不正な画像ハッシュ" }
            val target = File(directory, hash)
            if (target.isFile && sha256(target.readBytes()) == hash) return@forEach
            val remote = imageInventory[hash]
            if (remote == null) { missingImages++; return@forEach }
            val bytes = download(token, remote.getString("id"), 32 * 1024 * 1024)
            require(sha256(bytes) == hash) { "画像検証に失敗" }
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        }
        val updated = java.time.Instant.now().toString()
        val saved = JSONObject().put("updatedAt", updated).put("datasetId", PfcSyncSnapshot.DATASET_ID)
            .put("rows", snapshot.toJson())
        currentCoroutineContext().ensureActive()
        val output = cache.startWrite()
        try { output.write(saved.toString().toByteArray(Charsets.UTF_8)); cache.finishWrite(output) }
        catch (error: Exception) { cache.failWrite(output); throw error }
        PfcBoardState(boards, imageFiles(snapshot), status = when {
            boards.isEmpty() -> "比較ボードなし。PC側で保存・同期してください"
            missingImages > 0 -> "取得完了。未同期画像${missingImages}件（PCで画像同期を確認）"
            else -> "取得完了（閲覧専用）"
        }, updatedAt = updated)
    }

    private fun imageFiles(snapshot: PfcSyncSnapshot): Map<String, File> = snapshot.all("ImageAssetEntity")
        .mapNotNull { image ->
            val hash = image.optString("sha256").lowercase()
            if (!hash.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
            File(directory, hash).takeIf { it.isFile }?.let { image.getString("id").lowercase() to it }
        }.toMap()

    private fun listFiles(token: String): List<JSONObject> {
        val files = mutableListOf<JSONObject>()
        val seenPages = mutableSetOf<String>()
        var page = ""
        do {
            check(seenPages.add(page)) { "一覧のページ情報が重複" }
            val query = "'appDataFolder' in parents and trashed = false and (name contains 'pfc-sync-' or name contains 'pfc-image-')"
            val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1000" +
                "&fields=nextPageToken,incompleteSearch,files(id,name,appProperties)&q=${encode(query)}" +
                if (page.isEmpty()) "" else "&pageToken=${encode(page)}"
            val json = JSONObject(get(token, url, 8 * 1024 * 1024).toString(Charsets.UTF_8))
            check(!json.optBoolean("incompleteSearch")) { "一覧取得が未完了" }
            files += json.getJSONArray("files").objects()
            page = json.optString("nextPageToken")
        } while (page.isNotEmpty())
        return files
    }

    private fun download(token: String, id: String, limit: Int): ByteArray =
        get(token, "https://www.googleapis.com/drive/v3/files/${encode(id)}?alt=media", limit)

    /** Google公式の診断API。返すのは公開のOAuthプロジェクト番号だけ。token/応答本文は保存・表示しない。 */
    private fun oauthProjectNumber(token: String): String? = runCatching {
        val response = JSONObject(get(token,
            "https://oauth2.googleapis.com/tokeninfo?access_token=${encode(token)}", 64 * 1024).toString(Charsets.UTF_8))
        response.optString("aud").substringBefore('-').substringBefore('.')
            .takeIf { it.matches(Regex("[0-9]{6,}")) }
    }.getOrNull()

    private fun get(token: String, url: String, limit: Int): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (connection.responseCode != 200) throw PfcBoardReadException("Drive取得失敗 HTTP ${connection.responseCode}（権限・接続を確認）")
            return connection.inputStream.use { input ->
                val bytes = input.readBytesLimited(limit)
                bytes
            }
        } finally { connection.disconnect() }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw PfcBoardReadException("同期ファイルが取得上限を超過")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
