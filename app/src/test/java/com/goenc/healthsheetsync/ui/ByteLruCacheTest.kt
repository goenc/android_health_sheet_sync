package com.goenc.healthsheetsync.ui

import org.junit.Assert.*
import org.junit.Test

class ByteLruCacheTest {
    @Test fun `returning to a cached image keeps it and evicts the least recently used image`() {
        val cache = ByteLruCache<String, String>(6) { it.length }
        cache.put("a", "aaa")
        cache.put("b", "bbb")
        assertEquals("aaa", cache.get("a"))
        cache.put("c", "ccc")
        assertEquals("aaa", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("ccc", cache.get("c"))
        assertEquals(6, cache.bytes)
        assertFalse(cache.hasRoomFor(1))
    }

    @Test fun `replacement updates memory accounting and oversized images keep existing entries`() {
        val cache = ByteLruCache<String, String>(6) { it.length }
        cache.put("a", "aaaa")
        cache.put("a", "aa")
        assertEquals(2, cache.bytes)
        assertTrue(cache.hasRoomFor(4))
        cache.put("oversize", "1234567")
        assertNull(cache.get("oversize"))
        assertEquals("aa", cache.get("a"))
        assertEquals(2, cache.bytes)
    }
}
