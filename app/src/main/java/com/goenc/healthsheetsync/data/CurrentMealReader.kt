package com.goenc.healthsheetsync.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CurrentMealUiState(
    val plan: CurrentMealPlan? = null,
    val isLoading: Boolean = false,
    val status: String? = null,
)

internal data class CurrentMealReadResult(val plan: CurrentMealPlan?, val fileCount: Int)

/** 食事アプリの全同期履歴を読み取り専用で復元する。Driveへの書き込みは行わない。 */
internal class CurrentMealReader {
    suspend fun read(accessToken: String): CurrentMealReadResult = withContext(Dispatchers.IO) {
        val batches = listBatches(accessToken)
        val contents = coroutineScope {
            val semaphore = Semaphore(4)
            batches.map { batch ->
                async {
                    semaphore.withPermit {
                        request(accessToken, "/files/${Uri.encode(batch.id)}?alt=media")
                    }
                }
            }.awaitAll()
        }
        CurrentMealReadResult(parseCurrentMealBatches(contents), batches.size)
    }

    private fun listBatches(token: String): List<MealSyncBatch> {
        val files = mutableListOf<MealSyncBatch>()
        var listedCount = 0
        var page: String? = null
        do {
            val url = Uri.parse("$API/files").buildUpon()
                .appendQueryParameter("spaces", "appDataFolder")
                .appendQueryParameter("q", "'appDataFolder' in parents and trashed = false and name contains 'pfc-sync-'")
                .appendQueryParameter("pageSize", "1000")
                .appendQueryParameter("fields", "nextPageToken,files(id,name,mimeType,appProperties)")
            page?.let { url.appendQueryParameter("pageToken", it) }
            val response = request(token, url.build().toString().removePrefix(API))
            val array = response.optJSONArray("files") ?: JSONArray()
            listedCount += array.length()
            repeat(array.length()) { index ->
                parseMealSyncBatch(array.getJSONObject(index))?.let { files += it }
            }
            page = response.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        check(files.isNotEmpty()) {
            if (listedCount == 0) {
                "同期ファイルの取得0件。メシ残高と同じGoogleアカウントで認証してください。同じアカウントでも0件なら、両アプリのOAuth登録先を確認してください"
            } else {
                "同期ファイル候補${listedCount}件、対象0件。データセットIDまたは同期ファイルのアプリ専用プロパティを確認できませんでした"
            }
        }
        return files.sortedWith(compareBy<MealSyncBatch> { it.deviceId }
            .thenBy { it.deviceSequence }.thenBy { it.name }.thenBy { it.id })
    }

    private fun request(token: String, suffix: String): JSONObject {
        require(token.isNotBlank()) { "Google認証トークンを取得できませんでした" }
        val connection = (URL(API + suffix).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            check(code in 200..299) {
                when (code) {
                    401, 403 -> "食事データへのアクセス権がありません。Googleアカウントとアプリ専用領域のOAuth設定を確認してください（HTTP $code）"
                    else -> "食事データを取得できませんでした（HTTP $code）。更新で再試行してください"
                }
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
    }
}

internal const val MEAL_DATASET_ID = "9b0db1ba-7ee0-4cf3-9b9b-84f674a8d1bb"

internal data class MealSyncBatch(val id: String, val name: String, val deviceId: String, val deviceSequence: Long)

internal fun parseMealSyncBatch(file: JSONObject): MealSyncBatch? {
    val properties = file.optJSONObject("appProperties") ?: return null
    if (!properties.optString("datasetId").equals(MEAL_DATASET_ID, ignoreCase = true)) return null
    val id = file.optString("id").takeIf { it.isNotBlank() } ?: return null
    val deviceId = properties.optString("deviceId").takeIf { it.isNotBlank() } ?: return null
    val sequence = properties.optString("deviceSequence").toLongOrNull() ?: return null
    return MealSyncBatch(id, file.optString("name"), deviceId, sequence)
}
