package com.herabarman.meshnet.sim;

import static org.junit.jupiter.api.Assertions.*;

import com.herabarman.meshnet.core.DeliveryMode;

import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingTest {
    private static SimConfig small(double online) {
        SimConfig c = new SimConfig();
        c.nodes = 60;
        c.zoneSize = 10;      // 6 zones
        c.gatewayCount = 1;
        c.onlineRatio = online;
        c.jitterMs = 0;
        c.seed = 7;
        c.backbone = "chord";    // these tests are about the ring backbone + gateways
        c.mode = DeliveryMode.ONLINE_FIRST;   // and about the server-first behaviour
        return c;
    }

    private static final byte[] PAYLOAD = {1, 2, 3};

    @Test
    void localMessageIsFloodedInsideTheZone() {
        SimNetwork net = new SimNetwork(small(0.0));
        net.sendMessage(0, 7, PAYLOAD, 1);
        net.clock.runFor(5_000);
        assertEquals(1, net.metrics.delivered);
        assertEquals(0, net.metrics.viaServer);
    }

    /** First node of the zone that is not a gateway. */
    private static int plainNode(SimNetwork net, int zone, int zoneSize) {
        for (int id = zone * zoneSize; id < (zone + 1) * zoneSize; id++) {
            if (!net.node(id).isGateway()) return id;
        }
        throw new IllegalStateException("zone has only gateways");
    }

    @Test
    void remoteMessageTravelsOverTheBackbone() {
        SimConfig c = small(0.0);
        SimNetwork net = new SimNetwork(c);
        int src = plainNode(net, 0, c.zoneSize);
        int dst = plainNode(net, 4, c.zoneSize);
        net.sendMessage(src, dst, PAYLOAD, 1);
        net.clock.runFor(10_000);
        assertEquals(1, net.metrics.delivered);
        assertEquals(0, net.metrics.viaServer);
        // src -> own gateway, gateway -> gateway over the backbone, gateway -> dst: at least 3 hops
        assertTrue(net.metrics.avgHops() >= 3, "hops " + net.metrics.avgHops());
    }

    @Test
    void onlineNodesUseTheServerFastPath() {
        SimNetwork net = new SimNetwork(small(1.0));
        net.sendMessage(1, 45, PAYLOAD, 1);
        net.clock.runFor(5_000);
        assertEquals(1, net.metrics.delivered);
        assertEquals(1, net.metrics.viaServer);
        assertEquals(1.0, net.metrics.avgHops(), 0.0001);
    }

    @Test
    void floodingCreatesDuplicatesButDeliversOnce() {
        SimNetwork net = new SimNetwork(small(0.0));
        net.sendMessage(0, 5, PAYLOAD, 1);
        net.clock.runFor(5_000);
        assertEquals(1, net.metrics.delivered);
        assertTrue(net.metrics.duplicates > 0);
    }

    @Test
    void gatewayFailureTriggersReelectionAndDeliveryContinues() {
        SimConfig c = small(0.0);
        SimNetwork net = new SimNetwork(c);
        int oldGateway = net.gatewaysOf(0).get(0);
        net.killNode(oldGateway);
        net.clock.runFor(c.electionDelayMs + 1_000);
        List<Integer> now = net.gatewaysOf(0);
        assertEquals(1, now.size());
        assertNotEquals(oldGateway, (int) now.get(0));
        assertTrue(net.info(now.get(0)).alive);

        int src = (oldGateway == 1) ? 2 : 1; // any live node of zone 0
        net.sendMessage(src, 45, PAYLOAD, 1);
        net.clock.runFor(10_000);
        assertEquals(1, net.metrics.delivered);
    }

    @Test
    void packetsWaitInStoreAndForwardUntilTheDestinationZoneHasAGateway() {
        SimConfig c = small(0.0);
        SimNetwork net = new SimNetwork(c);
        int dstGateway = net.gatewaysOf(3).get(0);
        int dst = 30; // zone 3
        if (dst == dstGateway) dst = 31;
        net.killNode(dstGateway);   // destination zone has no gateway until the election runs
        net.sendMessage(1, dst, PAYLOAD, 5);
        net.clock.runFor(1_000);
        assertEquals(0, net.metrics.delivered);   // stuck at the zone's neighbours, waiting
        net.clock.runFor(40_000);                 // election + retry timer
        assertEquals(1, net.metrics.delivered);
    }

    @Test
    void encryptedRunDeliversToo() {
        SimConfig c = small(0.0);
        c.encrypt = true;
        SimNetwork net = new SimNetwork(c);
        net.sendMessage(1, 45, PAYLOAD, 1);
        net.clock.runFor(10_000);
        assertEquals(1, net.metrics.delivered);
        assertEquals(0, net.metrics.dropped);
    }

    @Test
    void wholeScenarioRunsAndMostMessagesArrive() {
        SimConfig c = new SimConfig();
        c.nodes = 600;
        c.zoneSize = 15;
        c.messages = 200;
        c.durationMs = 30_000;
        c.drainMs = 60_000;
        Metrics m = Simulation.run(c);
        assertTrue(m.sent > 150);
        assertTrue(m.deliveryRate() > 0.8, "delivery " + m.deliveryRate());
    }
}
