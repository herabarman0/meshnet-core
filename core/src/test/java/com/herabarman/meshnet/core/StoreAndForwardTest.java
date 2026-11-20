package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StoreAndForwardTest {
    private static Packet pkt(long id, int prio) {
        return new Packet(1, 10, prio, id, 1, 2, new byte[0]);
    }

    @Test
    void takesHighestPriorityFirstThenOldest() {
        StoreAndForward s = new StoreAndForward(10);
        s.enqueue(pkt(1, 1), 1000, 0);
        s.enqueue(pkt(2, 5), 1000, 0);
        s.enqueue(pkt(3, 5), 1000, 0);
        List<StoreAndForward.Item> out = s.takeAll(10);
        assertEquals(2L, out.get(0).packet.msgId);
        assertEquals(3L, out.get(1).packet.msgId);
        assertEquals(1L, out.get(2).packet.msgId);
        assertEquals(0, s.size());
    }

    @Test
    void expiredItemsAreDropped() {
        StoreAndForward s = new StoreAndForward(10);
        s.enqueue(pkt(1, 1), 100, 0);
        s.enqueue(pkt(2, 1), 5000, 0);
        List<StoreAndForward.Item> out = s.takeAll(200);
        assertEquals(1, out.size());
        assertEquals(2L, out.get(0).packet.msgId);
        assertEquals(1, s.expiredCount());
        assertFalse(s.enqueue(pkt(3, 1), 100, 200)); // already expired
    }

    @Test
    void fullStoreEvictsLowerPriorityOnly() {
        StoreAndForward s = new StoreAndForward(2);
        assertTrue(s.enqueue(pkt(1, 1), 1000, 0));
        assertTrue(s.enqueue(pkt(2, 2), 1000, 0));
        assertFalse(s.enqueue(pkt(3, 1), 1000, 0)); // not better than the worst
        assertTrue(s.enqueue(pkt(4, 9), 1000, 0));   // evicts pkt 1
        assertEquals(1, s.evictedCount());
        List<StoreAndForward.Item> out = s.takeAll(0);
        assertEquals(4L, out.get(0).packet.msgId);
        assertEquals(2L, out.get(1).packet.msgId);
    }
}
