package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

class StorageTest {
    static File tempDir(String name) {
        File d = new File(System.getProperty("java.io.tmpdir"), "meshtest-" + name + "-" + System.nanoTime());
        d.mkdirs();
        return d;
    }

    private static Packet pkt(long id, int prio) {
        return new Packet(1, 10, prio, id, 1, 2, new byte[]{9, 9});
    }

    @Test
    void fileStorageRoundTripWithAwkwardKeys() {
        FileStorage s = new FileStorage(tempDir("fs"));
        s.put("sf-0001", new byte[]{1, 2, 3});
        s.put("weird key/with:chars", new byte[]{4});
        assertArrayEquals(new byte[]{1, 2, 3}, s.get("sf-0001"));
        assertArrayEquals(new byte[]{4}, s.get("weird key/with:chars"));
        assertNull(s.get("missing"));
        s.put("sf-0001", new byte[]{7});                        // overwrite
        assertArrayEquals(new byte[]{7}, s.get("sf-0001"));
        List<String> keys = s.keys("weird");
        assertEquals(1, keys.size());
        assertEquals("weird key/with:chars", keys.get(0));
        s.remove("sf-0001");
        assertNull(s.get("sf-0001"));
        assertEquals(1, s.keys("").size());
    }

    @Test
    void storedPacketsSurviveARestartInPriorityOrder() {
        File dir = tempDir("sf");
        StoreAndForward a = new StoreAndForward(10, new FileStorage(dir));
        a.enqueue(pkt(1, 1), 100_000, 0);
        a.enqueue(pkt(2, 9), 100_000, 0);
        a.enqueue(pkt(3, 5), 100_000, 0);
        // app restarts: a brand new queue on the same directory
        StoreAndForward b = new StoreAndForward(10, new FileStorage(dir));
        assertEquals(3, b.restore(10));
        List<StoreAndForward.Item> out = b.takeAll(10);
        assertEquals(2L, out.get(0).packet.msgId);
        assertEquals(3L, out.get(1).packet.msgId);
        assertEquals(1L, out.get(2).packet.msgId);
    }

    @Test
    void doneRemovesFromDiskAndRequeueKeepsIt() {
        File dir = tempDir("done");
        FileStorage st = new FileStorage(dir);
        StoreAndForward a = new StoreAndForward(10, st);
        a.enqueue(pkt(1, 1), 100_000, 0);
        a.enqueue(pkt(2, 1), 100_000, 0);
        List<StoreAndForward.Item> items = a.takeAll(5);
        assertEquals(2, st.keys("sf-").size());      // taken but not finished: still on disk (crash safe)
        a.done(items.get(0));
        a.requeue(items.get(1));
        assertEquals(1, st.keys("sf-").size());
        assertEquals(1, a.size());
        StoreAndForward b = new StoreAndForward(10, st);
        assertEquals(1, b.restore(6));
    }

    @Test
    void expiredAndCorruptEntriesAreDroppedOnRestore() {
        MemoryStorage st = new MemoryStorage();
        StoreAndForward a = new StoreAndForward(10, st);
        a.enqueue(pkt(1, 1), 50, 0);       // expires soon
        a.enqueue(pkt(2, 1), 100_000, 0);
        st.put("sf-00000000000000ff", new byte[]{1, 2, 3}); // garbage
        StoreAndForward b = new StoreAndForward(10, st);
        assertEquals(1, b.restore(1_000));
        assertEquals(1, st.keys("sf-").size());
        assertEquals(1, b.expiredCount());
    }

    @Test
    void evictionAlsoRemovesFromDisk() {
        MemoryStorage st = new MemoryStorage();
        StoreAndForward a = new StoreAndForward(1, st);
        assertTrue(a.enqueue(pkt(1, 1), 1000, 0));
        assertTrue(a.enqueue(pkt(2, 9), 1000, 0));
        assertEquals(1, st.keys("sf-").size());
    }
}
