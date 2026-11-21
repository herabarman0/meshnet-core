package com.herabarman.meshnet.core;

import java.util.List;

/**
 * Tiny key-value store the engine uses to survive restarts (queued messages, unconfirmed messages).
 * Implement it over SQLite, Room, SharedPreferences, MMKV ... or use {@link FileStorage}.
 * Calls come from the engine thread only. Values are small (one packet each).
 */
public interface StorageAdapter {
    void put(String key, byte[] value);

    /** @return the value, or null if the key does not exist */
    byte[] get(String key);

    void remove(String key);

    /** @return all keys that start with prefix (any order) */
    List<String> keys(String prefix);
}
