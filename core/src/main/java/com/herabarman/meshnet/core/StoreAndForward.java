package com.herabarman.meshnet.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Holds packets that currently have no route. Priority ordered, expiring, size capped. */
public final class StoreAndForward {
    public static final class Item {
        public final Packet packet;
        public final long expiresAtMs;
        private final long seq;

        Item(Packet packet, long expiresAtMs, long seq) {
            this.packet = packet;
            this.expiresAtMs = expiresAtMs;
            this.seq = seq;
        }
    }

    private final int maxItems;
    private final List<Item> items = new ArrayList<>();
    private long seq;
    private int expired;
    private int evicted;

    public StoreAndForward(int maxItems) {
        this.maxItems = maxItems;
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
            evicted++;
        }
        items.add(new Item(p, expiresAtMs, seq++));
        return true;
    }

    /** Remove and return all live items, highest priority first, then oldest first. */
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

    private void purge(long nowMs) {
        Iterator<Item> it = items.iterator();
        while (it.hasNext()) {
            if (it.next().expiresAtMs <= nowMs) {
                it.remove();
                expired++;
            }
        }
    }

    public int size() { return items.size(); }
    public int expiredCount() { return expired; }
    public int evictedCount() { return evicted; }
}
