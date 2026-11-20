package com.herabarman.meshnet.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class FloodOptimizationTest {
    /** Static network (no churn, no loss), same traffic every time. */
    private static Metrics run(int jitter, int counter, int fanout) {
        SimConfig c = new SimConfig();
        c.nodes = 100;
        c.zoneSize = 20;      // 5 zones
        c.gatewayCount = 1;
        c.onlineRatio = 0.0;  // no server shortcut, everything goes through the mesh
        c.seed = 11;
        c.floodJitterMs = jitter;
        c.counterThreshold = counter;
        c.floodFanout = fanout;
        c.routeLearning = false;   // this test is about flooding only
        c.ackEnabled = false;
        SimNetwork net = new SimNetwork(c);
        Random r = new Random(5);
        for (int i = 0; i < 60; i++) {
            int src = r.nextInt(100);
            int dst = r.nextInt(100);
            if (src == dst) continue;
            net.sendMessage(src, dst, new byte[]{1, 2, 3}, 1);
            net.clock.runFor(2_000);
        }
        net.clock.runFor(60_000);
        return net.metrics;
    }

    @Test
    void senderSuppressionIsLosslessAndSavesTransmissions() {
        Metrics classic = run(0, 0, 0);
        Metrics smart = run(40, 0, 0);
        assertEquals(classic.sent, classic.delivered);
        assertEquals(smart.sent, smart.delivered);   // nothing lost: only peers that already have the packet are skipped
        assertTrue(smart.transmissions < classic.transmissions,
                "classic " + classic.transmissions + " vs smart " + smart.transmissions);
    }

    @Test
    void counterSuppressionKeepsDeliveryHighAndSuppressesSomeForwards() {
        Metrics classic = run(0, 0, 0);
        Metrics counter = run(40, 3, 0);
        assertTrue(counter.suppressed > 0);
        assertTrue(counter.transmissions < classic.transmissions);
        assertTrue(counter.deliveryRate() >= 0.95, "delivery " + counter.deliveryRate());
    }

    @Test
    void classicModeDoesNotSuppressAnything() {
        Metrics classic = run(0, 0, 0);
        assertEquals(0, classic.suppressed);
    }
}
