package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RouteCacheTest {
    @Test
    void storesAndReturnsNextHop() {
        RouteCache c = new RouteCache(10, 1000);
        assertEquals(-1, c.get(5, 0));
        c.put(5, 42, 0);
        assertEquals(42, c.get(5, 10));
        c.put(5, 43, 20); // a newer flood replaces the route
        assertEquals(43, c.get(5, 30));
    }

    @Test
    void entriesExpire() {
        RouteCache c = new RouteCache(10, 1000);
        c.put(5, 42, 0);
        assertEquals(42, c.get(5, 999));
        assertEquals(-1, c.get(5, 1000));
        assertEquals(0, c.size());
    }

    @Test
    void leastRecentlyUsedIsEvictedWhenFull() {
        RouteCache c = new RouteCache(2, 100000);
        c.put(1, 11, 0);
        c.put(2, 22, 0);
        c.get(1, 1);       // touch 1, so 2 is now the oldest
        c.put(3, 33, 2);
        assertEquals(11, c.get(1, 3));
        assertEquals(-1, c.get(2, 3));
        assertEquals(33, c.get(3, 3));
    }

    @Test
    void removeForgetsTheRoute() {
        RouteCache c = new RouteCache(10, 1000);
        c.put(5, 42, 0);
        c.remove(5);
        assertEquals(-1, c.get(5, 1));
    }
}
