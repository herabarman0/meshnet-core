package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ZoneRouterTest {
    @Test
    void ringDistanceWrapsAround() {
        assertEquals(1, ZoneRouter.ringDistance(0, 9, 10));
        assertEquals(5, ZoneRouter.ringDistance(0, 5, 10));
        assertEquals(0, ZoneRouter.ringDistance(3, 3, 10));
    }

    @Test
    void backboneLinksAreSymmetric() {
        int z = 100;
        for (int a = 0; a < z; a++) {
            for (int b : ZoneRouter.backboneNeighbors(a, z, 0)) {
                assertTrue(ZoneRouter.backboneNeighbors(b, z, 0).contains(a));
            }
        }
    }

    @Test
    void fanOutStaysLogarithmic() {
        Set<Integer> nb = ZoneRouter.backboneNeighbors(0, 5000, 0);
        assertTrue(nb.size() <= 2 * 13, "fan-out " + nb.size());
        assertTrue(ZoneRouter.backboneNeighbors(0, 5000, 6).size() <= 6);
    }

    @Test
    void greedyRoutingReachesEveryZoneInLogHops() {
        int zones = 64;
        int bound = 7; // log2(64) + 1
        for (int from = 0; from < zones; from++) {
            for (int to = 0; to < zones; to++) {
                int cur = from;
                int hops = 0;
                while (cur != to) {
                    int next = ZoneRouter.chooseNextHop(cur, to, zones,
                            ZoneRouter.backboneNeighbors(cur, zones, 0), z -> z, p -> true);
                    assertTrue(next >= 0, "stuck at " + cur + " going to " + to);
                    cur = next;
                    hops++;
                    assertTrue(hops <= bound, from + "->" + to + " took " + hops);
                }
            }
        }
    }

    @Test
    void noHopWhenNothingGetsCloser() {
        // only peer is farther away than we are
        int next = ZoneRouter.chooseNextHop(5, 6, 20, Arrays.asList(15), z -> z, p -> true);
        assertEquals(-1, next);
    }

    @Test
    void unusablePeersAreSkipped() {
        int next = ZoneRouter.chooseNextHop(0, 8, 20, Arrays.asList(7, 4), z -> z, p -> p != 7);
        assertEquals(4, next);
    }
}
