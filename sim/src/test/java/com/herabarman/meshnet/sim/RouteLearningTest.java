package com.herabarman.meshnet.sim;

import static org.junit.jupiter.api.Assertions.*;

import com.herabarman.meshnet.core.MeshNode;
import com.herabarman.meshnet.core.Packet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteLearningTest {
    private static final byte[] PAYLOAD = {1, 2, 3};

    private static SimConfig cfg(boolean ack) {
        SimConfig c = new SimConfig();
        c.nodes = 40;
        c.zoneSize = 20;       // 2 zones
        c.gatewayCount = 1;
        c.onlineRatio = 0.0;   // everything goes through the mesh
        c.seed = 3;
        c.ackEnabled = ack;
        c.routeLearning = true;
        return c;
    }

    /** Two plain nodes of zone 0 that are not direct neighbours, so a route has at least 2 hops. */
    private static int[] farPair(SimNetwork net) {
        for (int a = 0; a < 20; a++) {
            for (int b = a + 1; b < 20; b++) {
                if (!net.node(a).isGateway() && !net.node(b).isGateway() && !net.linksOf(a).contains(b)) {
                    return new int[]{a, b};
                }
            }
        }
        throw new IllegalStateException("no far pair");
    }

    private static long cost(SimNetwork net, int src, int dst) {
        long before = net.metrics.transmissions;
        net.sendMessage(src, dst, PAYLOAD, 1);
        net.clock.runFor(10_000);
        return net.metrics.transmissions - before;
    }

    @Test
    void firstMessageFloodsThenReplyAndFollowUpAreCheap() {
        SimNetwork net = new SimNetwork(cfg(true));
        int[] pair = farPair(net);
        int a = pair[0], b = pair[1];
        long first = cost(net, a, b);     // flood + ACK
        long reply = cost(net, b, a);     // b learned the way back to a from that flood
        long again = cost(net, a, b);     // a learned the way to b from the ACK
        assertEquals(3, net.metrics.delivered);
        assertTrue(reply * 2 < first, "first " + first + " reply " + reply);
        assertTrue(again * 2 < first, "first " + first + " again " + again);
    }

    @Test
    void learnedRouteIsALiveNeighbour() {
        SimNetwork net = new SimNetwork(cfg(true));
        int[] pair = farPair(net);
        net.sendMessage(pair[0], pair[1], PAYLOAD, 1);
        net.clock.runFor(10_000);
        int next = net.node(pair[0]).routeTo(pair[1]);
        assertTrue(next >= 0);
        assertTrue(net.linksOf(pair[0]).contains(next));
        assertTrue(next != pair[1]); // not adjacent, so the route has to go through someone
    }

    @Test
    void ackStopsRetries() {
        SimNetwork net = new SimNetwork(cfg(true));
        int[] pair = farPair(net);
        cost(net, pair[0], pair[1]);
        cost(net, pair[1], pair[0]);
        net.clock.runFor(60_000);
        assertEquals(2, net.metrics.acked);
        assertEquals(0, net.metrics.retries);
        assertEquals(0, net.metrics.unacked);
    }

    @Test
    void routeThroughADeadNodeIsNotUsed() {
        SimNetwork net = new SimNetwork(cfg(true));
        int[] pair = farPair(net);
        int a = pair[0], b = pair[1];
        cost(net, a, b);
        cost(net, b, a);
        int next = net.node(a).routeTo(b);
        net.killNode(next);
        net.sendMessage(a, b, PAYLOAD, 1);
        net.clock.runFor(10_000);
        assertEquals(3, net.metrics.delivered);
        assertEquals(0, net.metrics.retries); // the dead hop was skipped at once, no timeout needed
    }

    @Test
    void silentLossIsRecoveredByAckTimeoutAndFloodFallback() {
        int withAck = deliveredUnderLoss(true);
        int withoutAck = deliveredUnderLoss(false);
        assertTrue(withAck > withoutAck + 3, "with ACK " + withAck + " without " + withoutAck);
    }

    private static int deliveredUnderLoss(boolean ack) {
        SimNetwork net = new SimNetwork(cfg(ack));
        int[] pair = farPair(net);
        cost(net, pair[0], pair[1]);          // learn routes on a clean network
        cost(net, pair[1], pair[0]);
        net.cfg.lossRate = 0.35;              // now links silently drop packets
        long before = net.metrics.delivered;
        for (int i = 0; i < 30; i++) {
            net.sendMessage(pair[0], pair[1], PAYLOAD, 1);
            net.clock.runFor(30_000);
        }
        return (int) (net.metrics.delivered - before);
    }

    @Test
    void retransmissionsAreDeliveredOnlyOnce() {
        SimNetwork net = new SimNetwork(cfg(true));
        int[] pair = farPair(net);
        final Set<Long> seen = new HashSet<>();
        final int[] twice = {0};
        net.node(pair[1]).setListener(new MeshNode.Listener() {
            @Override public void onDelivered(MeshNode n, Packet p, byte[] plain, int from) {
                if (!seen.add(p.msgId)) twice[0]++;
            }
            @Override public void onDuplicate(MeshNode n, Packet p) {}
            @Override public void onDropped(MeshNode n, Packet p, String reason) {}
        });
        cost(net, pair[0], pair[1]);
        net.cfg.lossRate = 0.4;
        for (int i = 0; i < 25; i++) {
            net.sendMessage(pair[0], pair[1], PAYLOAD, 1);
            net.clock.runFor(30_000);
        }
        assertTrue(net.metrics.retries > 0, "scenario should force retransmissions");
        assertTrue(seen.size() > 10);
        assertEquals(0, twice[0]);
    }
}
