package com.herabarman.meshnet.core;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;

/**
 * Zone addressing and backbone routing.
 * Zones sit on a ring. Each zone links to zones at +-1, +-2, +-4, ... (Chord-like fingers),
 * so fan-out stays around 2*log2(zones) and greedy routing needs about log2(zones) hops.
 */
public final class ZoneRouter {
    private ZoneRouter() {}

    /** nodeId -> zoneId. */
    public interface ZoneMap {
        int zoneOf(int nodeId);
    }

    /** Simplest zone assignment: ids 0..zoneSize-1 are zone 0, the next zoneSize ids zone 1, and so on. */
    public static ZoneMap blockZones(final int zoneSize) {
        return new ZoneMap() {
            @Override
            public int zoneOf(int nodeId) {
                return nodeId / zoneSize;
            }
        };
    }

    public static int ringDistance(int a, int b, int zoneCount) {
        int d = Math.abs(a - b);
        return Math.min(d, zoneCount - d);
    }

    private static int mod(int x, int n) {
        return ((x % n) + n) % n;
    }

    /** Zones this zone links to on the backbone. maxFanOut <= 0 means no cap. */
    public static Set<Integer> backboneNeighbors(int zone, int zoneCount, int maxFanOut) {
        Set<Integer> out = new TreeSet<>();
        if (zoneCount <= 1) return out;
        int maxOffsets = maxFanOut <= 0 ? Integer.MAX_VALUE : Math.max(1, maxFanOut / 2);
        int k = 0;
        for (int off = 1; off <= zoneCount / 2 && k < maxOffsets; off <<= 1, k++) {
            out.add(mod(zone + off, zoneCount));
            out.add(mod(zone - off, zoneCount));
        }
        out.remove(zone);
        return out;
    }

    /**
     * Greedy next hop: the usable backbone peer whose zone is strictly closer to dstZone
     * than ours. Returns -1 if none (caller stores and retries).
     */
    public static int chooseNextHop(int myZone, int dstZone, int zoneCount,
                                    Collection<Integer> peers, ZoneMap zones, IntPredicate usable) {
        int best = -1;
        int bestDist = ringDistance(myZone, dstZone, zoneCount);
        for (int peer : peers) {
            if (!usable.test(peer)) continue;
            int d = ringDistance(zones.zoneOf(peer), dstZone, zoneCount);
            if (d < bestDist) {
                bestDist = d;
                best = peer;
            }
        }
        return best;
    }
}
