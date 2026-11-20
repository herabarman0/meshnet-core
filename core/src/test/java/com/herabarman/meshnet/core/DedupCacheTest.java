package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DedupCacheTest {
    @Test
    void secondSightingIsDuplicate() {
        DedupCache c = new DedupCache(100, 1000);
        assertTrue(c.firstSeen(1L, 0));
        assertFalse(c.firstSeen(1L, 10));
        assertTrue(c.firstSeen(2L, 10));
    }

    @Test
    void entriesExpire() {
        DedupCache c = new DedupCache(100, 1000);
        assertTrue(c.firstSeen(1L, 0));
        assertFalse(c.firstSeen(1L, 999));
        assertTrue(c.firstSeen(1L, 1001));
    }

    @Test
    void sizeIsCapped() {
        DedupCache c = new DedupCache(3, 100000);
        for (long i = 0; i < 10; i++) c.firstSeen(i, 0);
        assertEquals(3, c.size());
        assertTrue(c.firstSeen(0L, 1)); // oldest was evicted
        assertFalse(c.firstSeen(9L, 1)); // newest is still known
    }
}
