package com.goenc.healthsheetsync.ui

/** Small synchronized LRU shared by visible image requests and background prefetch. */
internal class ByteLruCache<K, V>(private val maxBytes: Int, private val sizeOf: (V) -> Int) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    var bytes: Int = 0
        private set

    @Synchronized fun get(key: K): V? = entries[key]

    @Synchronized fun put(key: K, value: V) {
        val size = sizeOf(value)
        if (size > maxBytes) return
        entries.remove(key)?.let { bytes -= sizeOf(it) }
        entries[key] = value
        bytes += size
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= sizeOf(iterator.next().value)
            iterator.remove()
        }
    }

    @Synchronized fun hasRoomFor(bytes: Int): Boolean = this.bytes + bytes <= maxBytes
}
