package com.herabarman.meshnet.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Non-persistent storage: for tests and for apps that do not want persistence. */
public final class MemoryStorage implements StorageAdapter {
    private final Map<String, byte[]> map = new TreeMap<>();

    @Override public synchronized void put(String key, byte[] value) { map.put(key, value.clone()); }
    @Override public synchronized byte[] get(String key) { byte[] v = map.get(key); return v == null ? null : v.clone(); }
    @Override public synchronized void remove(String key) { map.remove(key); }

    @Override
    public synchronized List<String> keys(String prefix) {
        List<String> out = new ArrayList<>();
        for (String k : map.keySet()) if (k.startsWith(prefix)) out.add(k);
        return out;
    }

    public synchronized int size() { return map.size(); }
}
