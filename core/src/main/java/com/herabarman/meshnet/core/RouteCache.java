package com.herabarman.meshnet.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small LRU map: destination key -> next-hop peer, with expiry. */
public final class RouteCache {
    private static final class Entry {
        final int nextHop;
        final long expiresAtMs;

        Entry(int nextHop, long expiresAtMs) {
            this.nextHop = nextHop;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final long ttlMs;
    private final LinkedHashMap<Integer, Entry> map;

    public RouteCache(final int maxEntries, long ttlMs) {
        this.ttlMs = ttlMs;
        this.map = new LinkedHashMap<Integer, Entry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public void put(int key, int nextHop, long nowMs) {
        map.put(key, new Entry(nextHop, nowMs + ttlMs));
    }

    /** @return next hop, or -1 if unknown or expired */
    public int get(int key, long nowMs) {
        Entry e = map.get(key);
        if (e == null) return -1;
        if (e.expiresAtMs <= nowMs) {
            map.remove(key);
            return -1;
        }
        return e.nextHop;
    }

    public void remove(int key) {
        map.remove(key);
    }

    public int size() {
        return map.size();
    }
}
