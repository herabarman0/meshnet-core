package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayManagerTest {
    private final GatewayManager gm = new GatewayManager();

    private static List<NodeInfo> zone() {
        List<NodeInfo> l = new ArrayList<>();
        l.add(new NodeInfo(0, 0, 0.5, 0.5, false));
        l.add(new NodeInfo(1, 0, 0.9, 0.9, true));  // best
        l.add(new NodeInfo(2, 0, 0.8, 0.8, false));
        l.add(new NodeInfo(3, 0, 0.3, 0.3, false));
        return l;
    }

    @Test
    void electsHighestScore() {
        assertEquals(Arrays.asList(1, 2), gm.elect(zone(), 2));
    }

    @Test
    void internetBeatsBatteryAndSignalAlone() {
        List<NodeInfo> l = new ArrayList<>();
        l.add(new NodeInfo(0, 0, 0.6, 0.6, true));
        l.add(new NodeInfo(1, 0, 0.8, 0.8, false));
        assertEquals(Arrays.asList(0), gm.elect(l, 1));
    }

    @Test
    void deadNodesAreNeverElected() {
        List<NodeInfo> l = zone();
        l.get(1).alive = false;
        assertEquals(Arrays.asList(2, 0), gm.elect(l, 2));
    }

    @Test
    void reelectKeepsHealthyGatewaysAndFillsVacancy() {
        List<NodeInfo> l = zone();
        List<Integer> current = Arrays.asList(1, 2);
        l.get(1).alive = false; // gateway 1 failed
        List<Integer> next = gm.reelect(l, current, 2);
        assertEquals(2, next.size());
        assertTrue(next.contains(2));      // healthy gateway keeps its role
        assertFalse(next.contains(1));     // dead one is gone
        assertTrue(next.contains(0));      // best remaining fills the slot
    }

    @Test
    void reelectDoesNotFlapWhenBetterNodeAppears() {
        List<NodeInfo> l = zone();
        List<Integer> current = Arrays.asList(2, 3); // both alive, not the best
        assertEquals(current, gm.reelect(l, current, 2));
    }

    @Test
    void emptyZoneGivesNoGateways() {
        List<NodeInfo> l = zone();
        for (NodeInfo n : l) n.alive = false;
        assertTrue(gm.elect(l, 2).isEmpty());
    }
}
