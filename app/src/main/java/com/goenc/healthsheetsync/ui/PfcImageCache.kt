package com.goenc.healthsheetsync.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Files are named by SHA-256, so replacing an image automatically changes its cache key. */
internal object PfcImageCache {
    private data class Key(val path: String, val edge: Int)
    private val cache = ByteLruCache<Key, ImageBitmap>(16 * 1024 * 1024) { it.width * it.height * 4 }
    private val decodeLock = Mutex()

    fun peek(file: File?, edge: Int): ImageBitmap? = file?.let { cache.get(Key(it.absolutePath, edge)) }

    suspend fun load(file: File?, edge: Int, prefetch: Boolean = false): ImageBitmap? {
        if (file == null) return null
        peek(file, edge)?.let { return it }
        return withContext(Dispatchers.IO) {
            decodeLock.withLock {
                peek(file, edge)?.let { return@withLock it }
                if (prefetch && !cache.hasRoomFor(edge * edge * 4)) return@withLock null
                val bitmap = runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > edge || bounds.outHeight / sample > edge) sample *= 2
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.apply { prepareToDraw() }
                        ?.asImageBitmap()
                }.getOrNull()
                bitmap?.also { cache.put(Key(file.absolutePath, edge), it) }
            }
        }
    }

    suspend fun prefetch(files: Collection<File>, initialPlan: Boolean = false) {
        for (file in files.distinctBy { it.absolutePath }) {
            // Do not let speculative reads evict images already used by the user.
            if (peek(file, 128) != null) continue
            if (!initialPlan && !cache.hasRoomFor(128 * 128 * 4)) return
            load(file, 128, prefetch = !initialPlan)
            // The initial plan is prepared immediately after the main screen draws.
            if (!initialPlan) delay(16)
        }
    }
}
