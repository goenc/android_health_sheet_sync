package com.goenc.healthsheetsync.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.goenc.healthsheetsync.data.LocalHealthDataStore
import com.goenc.healthsheetsync.data.OneTouchRevealTextParser

class SharedTextImporter(
    private val context: Context,
    private val localStore: LocalHealthDataStore,
) {
    fun importFrom(intent: Intent?): SharedTextImportResult? {
        if (intent == null || intent.action !in SUPPORTED_ACTIONS) {
            return null
        }
        val text = readSharedText(intent)
        if (text.isBlank()) return null

        val glucoseRecords = OneTouchRevealTextParser.parse(text)
        if (glucoseRecords.isEmpty()) {
            return SharedTextImportResult(
                text = text,
                status = "共有テキストから血糖値を読み取れませんでした",
                imported = false,
            )
        }

        localStore.save(
            weightRecords = emptyList(),
            glucoseRecords = glucoseRecords,
            stepDailyRecords = emptyList(),
        )
        return SharedTextImportResult(
            text = text,
            status = "共有テキストから血糖${glucoseRecords.size}件を取り込みました",
            imported = true,
        )
    }

    private fun readSharedText(intent: Intent): String {
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val streamTexts = buildList {
            intent.data?.let { uri ->
                readTextFromUri(uri)?.let(::add)
            }
            intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                readTextFromUri(uri)?.let(::add)
            }
            intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)
                ?.mapNotNull(::readTextFromUri)
                ?.let(::addAll)
            intent.clipData?.let { clipData ->
                for (index in 0 until clipData.itemCount) {
                    clipData.getItemAt(index).uri?.let { uri ->
                        readTextFromUri(uri)?.let(::add)
                    }
                }
            }
        }
        return (listOf(extraText) + streamTexts)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun readTextFromUri(uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read shared file: $uri", error)
            null
        }
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name) as? T
        }
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(
        name: String,
    ): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(name)
        }
    }

    private companion object {
        private val SUPPORTED_ACTIONS = setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)
        private const val TAG = "HealthSheetSync"
    }
}

data class SharedTextImportResult(
    val text: String,
    val status: String,
    val imported: Boolean,
)
