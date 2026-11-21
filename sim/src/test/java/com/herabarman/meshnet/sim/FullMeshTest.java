package com.herabarman.meshnet.sim;

import static org.junit.jupiter.api.Assertions.*;

import com.herabarman.meshnet.core.DeliveryMode;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FullMeshTest {
    private static final byte[] PAYLOAD = {1, 2, 3};

    private static SimConfig cfg() {
        SimConfig c = new SimConfig();
        c.nodes = 120;
        c.zoneSize = 12;       // 10 zones
        c.onlineRatio = 0.0;   // pure offline mesh
        c.jitterMs = 0;
        c.seed = 5;
        c.backbone = "auto";
        c.linksPerPair = 2;
        return c;
    }

    /** Number of live offline links between each pair of zones. */
    private static Map<Integer, Integer> linksPerPair(SimNetwork net) {
        Map<Integer, Integer> out = new HashMap<>();
        for (int[] e : net.edges()) {
            int a = net.zoneOf(e[0]), b = net.zoneOf(e[1]);
            if (a == b || !net.info(e[0]).alive || !net.info(e[1]).alive) continue;
            int key = Math.min(a, b) * 1000 + Math.max(a, b);
            out.merge(key, 1, Integer::sum);
        }
        return out;
    }

    private static int plainNode(SimNetwork net, int zone, int zoneSize) {
        for (int id = zone * zoneSize; id < (zone + 1) * zoneSize; id++) {
            if (!net.node(id).isGateway()) return id;
        }
        return zone * zoneSize;
    }

    @Test
    void everyZoneIsLinkedOfflineToEveryOtherZone() {
        SimNetwork net = new SimNetwork(cfg());
        assertTrue(net.isFullMesh());
        Map<Integer, Integer> pairs = linksPerPair(net);
        for (int a = 0; a < net.zoneCount; a++) {
            for (int b = a + 1; b < net.zoneCount; b++) {
                Integer n = pairs.get(a * 1000 + b);
                assertTrue(n != null && n >= 2, "zones " + a + " and " + b + " have " + n + " links");
            }
        }
    }

    @Test
    void borderLoadIsSpreadOverManyNodesNotOneGateway() {
        SimNetwork net = new SimNetwork(cfg());
        for (int z = 0; z < net.zoneCount; z++) {
            assertTrue(net.gatewaysOf(z).size() >= 6, "zone " + z + " has only " + net.gatewaysOf(z).size() + " border nodes");
        }
    }

    @Test
    void messagesCrossZonesOverTheMeshWithoutAnyServer() {
        SimConfig c = cfg();
        SimNetwork net = new SimNetwork(c);
        for (int z = 1; z < net.zoneCount; z++) {
            net.sendMessage(plainNode(net, 0, c.zoneSize), plainNode(net, z, c.zoneSize), PAYLOAD, 1);
            net.clock.runFor(8_000);
        }
        assertEquals(net.zoneCount - 1, net.metrics.delivered);
        assertEquals(0, net.metrics.viaServer);
    }

    @Test
    void losingBorderNodesNeverDisconnectsZones() {
        SimConfig c = cfg();
        SimNetwork net = new SimNetwork(c);
        for (int g : net.gatewaysOf(3).subList(0, 3)) net.killNode(g);   // three border nodes of zone 3 die
        int dst = -1;
        for (int id = 3 * c.zoneSize; id < 4 * c.zoneSize && dst < 0; id++) if (net.info(id).alive) dst = id;
        // redundant links carry traffic at once, repair restores the link count after the election delay
        net.sendMessage(plainNode(net, 7, c.zoneSize), dst, PAYLOAD, 1);
        net.clock.runFor(20_000);
        assertEquals(1, net.metrics.delivered);
        Map<Integer, Integer> pairs = linksPerPair(net);
        for (int a = 0; a < net.zoneCount; a++) {
            if (a == 3) continue;
            Integer n = pairs.get(Math.min(a, 3) * 1000 + Math.max(a, 3));
            assertTrue(n != null && n >= 2, "zone " + a + " <-> 3 has " + n + " links after repair");
        }
    }

    @Test
    void serverOnlyHelpsItDoesNotReplaceTheMesh() {
        SimConfig c = cfg();
        c.onlineRatio = 1.0;                  // everyone has internet
        SimNetwork net = new SimNetwork(c);
        net.sendMessage(plainNode(net, 0, c.zoneSize), plainNode(net, 6, c.zoneSize), PAYLOAD, 1);
        net.clock.runFor(8_000);
        assertEquals(1, net.metrics.delivered);
        assertEquals(0, net.metrics.viaServer);   // the mesh carried it although the server was available
    }

    @Test
    void serverRescuesAMessageWhenTheMeshFails() {
        SimConfig c = cfg();
        c.onlineRatio = 1.0;
        SimNetwork net = new SimNetwork(c);
        net.cfg.lossRate = 1.0;                   // every mesh packet is lost, the server path is unaffected
        net.sendMessage(plainNode(net, 0, c.zoneSize), plainNode(net, 6, c.zoneSize), PAYLOAD, 1);
        net.clock.runFor(20_000);                 // ACK timeout, then the retry goes through the server
        assertEquals(1, net.metrics.delivered);
        assertEquals(1, net.metrics.viaServer);
        assertTrue(net.metrics.retries >= 1);
    }

    @Test
    void onlineFirstModeStillAvailable() {
        SimConfig c = cfg();
        c.onlineRatio = 1.0;
        c.mode = DeliveryMode.ONLINE_FIRST;
        SimNetwork net = new SimNetwork(c);
        net.sendMessage(plainNode(net, 0, c.zoneSize), plainNode(net, 6, c.zoneSize), PAYLOAD, 1);
        net.clock.runFor(5_000);
        assertEquals(1, net.metrics.viaServer);
    }

    @Test
    void offlineOnlyNeverTouchesTheServerEvenWhenTheMeshIsDown() {
        SimConfig c = cfg();
        c.onlineRatio = 1.0;
        c.mode = DeliveryMode.OFFLINE_ONLY;
        SimNetwork net = new SimNetwork(c);
        net.cfg.lossRate = 1.0;
        net.sendMessage(plainNode(net, 0, c.zoneSize), plainNode(net, 6, c.zoneSize), PAYLOAD, 1);
        net.clock.runFor(60_000);
        assertEquals(0, net.metrics.delivered);
        assertEquals(0, net.metrics.viaServer);
    }

    @Test
    void manyZonesFallBackToTheRingBackbone() {
        SimConfig c = new SimConfig();
        c.nodes = 2_000;
        c.zoneSize = 15;       // 133 zones, more than fullMeshMaxZones
        SimNetwork net = new SimNetwork(c);
        assertFalse(net.isFullMesh());
        c.backbone = "full";   // forcing it is allowed
        assertTrue(new SimNetwork(c).isFullMesh());
    }
}
