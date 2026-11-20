package com.herasoft.meshnet.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers recently seen message ids. Entries expire by time and by size cap. */
public final class DedupCache {
    private final long ttlMs;
    private final LinkedHashMap<Long, Long> seen;

    public DedupCache(final int maxEntries, long ttlMs) {
        this.ttlMs = ttlMs;
        this.seen = new LinkedHashMap<Long, Long>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** @return true if this id was not seen (or its entry expired); it is now recorded. */
    public boolean firstSeen(long msgId, long nowMs) {
        Long expires = seen.get(msgId);
        if (expires != null && expires > nowMs) return false;
        seen.remove(msgId);
        seen.put(msgId, nowMs + ttlMs);
        return true;
    }

    public int size() {
        return seen.size();
    }
}
