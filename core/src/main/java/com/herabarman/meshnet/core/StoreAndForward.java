package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Holds packets that currently have no route. Priority ordered, expiring, size capped.
 * With a {@link StorageAdapter} every queued packet is also on disk, so it survives an app restart.
 *
 * Usage pattern: takeAll() hands out the items; for each one call done() if it was sent, or
 * requeue() if there is still no route. Items that were taken but not finished stay on disk, so a crash
 * in between delivers them at least once after the restart.
 */
public final class StoreAndForward {
    private static final String PREFIX = "sf-";

    public static final class Item {
        public final Packet packet;
        public final long expiresAtMs;
        private final long seq;
        private final String key; // null when not persistent

        Item(Packet packet, long expiresAtMs, long seq, String key) {
            this.packet = packet;
            this.expiresAtMs = expiresAtMs;
            this.seq = seq;
            this.key = key;
        }
    }

    private final int maxItems;
    private final StorageAdapter storage;
    private final List<Item> items = new ArrayList<>();
    private long seq;
    private int expired;
    private int evicted;

    public StoreAndForward(int maxItems) {
        this(maxItems, null);
    }

    public StoreAndForward(int maxItems, StorageAdapter storage) {
        this.maxItems = maxItems;
        this.storage = storage;
    }

    /** Load packets left on disk by an earlier run. @return how many were restored */
    public int restore(long nowMs) {
        if (storage == null) return 0;
        int restored = 0;
        for (String key : storage.keys(PREFIX)) {
            byte[] raw = storage.get(key);
            try {
                if (raw == null || raw.length < 8 + Packet.HEADER_SIZE) throw new IllegalArgumentException("short");
                ByteBuffer b = ByteBuffer.wrap(raw);
                long expiresAt = b.getLong();
                byte[] pk = new byte[raw.length - 8];
                b.get(pk);
                Packet p = Packet.decode(pk);
                long s = Long.parseLong(key.substring(PREFIX.length()), 16);
                seq = Math.max(seq, s + 1);
                if (expiresAt <= nowMs) {
                    storage.remove(key);
                    expired++;
                } else {
                    items.add(new Item(p, expiresAt, s, key));
                    restored++;
                }
            } catch (IllegalArgumentException e) {
                storage.remove(key); // corrupt entry
            }
        }
        return restored;
    }

    /**
     * @return false if the packet was not stored (already expired, or the store is full of
     *         packets with equal or higher priority). When full, the oldest lowest-priority
     *         packet is evicted if the new one has strictly higher priority.
     */
    public boolean enqueue(Packet p, long expiresAtMs, long nowMs) {
        purge(nowMs);
        if (expiresAtMs <= nowMs) return false;
        if (items.size() >= maxItems) {
            Item worst = null;
            for (Item it : items) {
                if (worst == null
                        || it.packet.priority < worst.packet.priority
                        || (it.packet.priority == worst.packet.priority && it.seq < worst.seq)) {
                    worst = it;
                }
            }
            if (worst == null || worst.packet.priority >= p.priority) return false;
            items.remove(worst);
            forget(worst);
            evicted++;
        }
        long s = seq++;
        String key = null;
        if (storage != null) {
            key = PREFIX + String.format("%016x", s);
            byte[] pk = p.encode();
            storage.put(key, ByteBuffer.allocate(8 + pk.length).putLong(expiresAtMs).put(pk).array());
        }
        items.add(new Item(p, expiresAtMs, s, key));
        return true;
    }

    /** Remove and return all live items, highest priority first, then oldest first. Call done() or requeue() for each. */
    public List<Item> takeAll(long nowMs) {
        purge(nowMs);
        List<Item> out = new ArrayList<>(items);
        items.clear();
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                int c = Integer.compare(b.packet.priority, a.packet.priority);
                return c != 0 ? c : Long.compare(a.seq, b.seq);
            }
        });
        return out;
    }

    /** The item was handled: delete it from disk. */
    public void done(Item item) {
        forget(item);
    }

    /** Still no route: put the item back (same disk entry, no rewrite). */
    public void requeue(Item item) {
        items.add(item);
    }

    private void forget(Item item) {
        if (storage != null && item.key != null) storage.remove(item.key);
    }

    private void purge(long nowMs) {
        Iterator<Item> it = items.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (item.expiresAtMs <= nowMs) {
                it.remove();
                forget(item);
                expired++;
            }
        }
    }

    public int size() { return items.size(); }
    public int expiredCount() { return expired; }
    public int evictedCount() { return evicted; }
}
