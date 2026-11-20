package com.herasoft.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PacketTest {
    @Test
    void roundTrip() {
        Packet p = new Packet(Packet.TYPE_DATA, 30, 7, 0x1122334455667788L, 42, 99, new byte[]{1, 2, 3});
        byte[] bytes = p.encode();
        assertEquals(Packet.HEADER_SIZE + 3, bytes.length);
        Packet q = Packet.decode(bytes);
        assertEquals(30, q.ttl);
        assertEquals(7, q.priority);
        assertEquals(0x1122334455667788L, q.msgId);
        assertEquals(42, q.src);
        assertEquals(99, q.dst);
        assertArrayEquals(new byte[]{1, 2, 3}, q.payload);
    }

    @Test
    void headerIsCompact() {
        assertEquals(22, Packet.HEADER_SIZE);
        assertEquals(22, new Packet(1, 1, 0, 1L, 1, 2, new byte[0]).encode().length);
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> Packet.decode(new byte[5]));
        byte[] good = new Packet(1, 5, 0, 1L, 1, 2, new byte[]{9}).encode();
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 1);
        assertThrows(IllegalArgumentException.class, () -> Packet.decode(truncated));
        good[0] = 99; // bad version
        assertThrows(IllegalArgumentException.class, () -> Packet.decode(good));
    }

    @Test
    void withTtlKeepsEverythingElse() {
        Packet p = new Packet(1, 10, 3, 5L, 1, 2, new byte[]{1});
        Packet q = p.withTtl(9);
        assertEquals(9, q.ttl);
        assertEquals(5L, q.msgId);
        assertEquals(3, q.priority);
    }
}
