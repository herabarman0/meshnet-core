package com.herabarman.meshnet.sim;

import com.herabarman.meshnet.core.NodeInfo;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Scenario driver: traffic + churn + gateway failure on top of SimNetwork. */
public final class Simulation {
    private Simulation() {}

    public static Metrics run(final SimConfig cfg) {
        final SimNetwork net = new SimNetwork(cfg);
        final Random r = new Random(cfg.seed + 1);
        net.metrics.backbone = net.isFullMesh() ? "full zone mesh" : "ring+shortcuts";

        // Churn: every interval some nodes go down and come back 5-20 s later.
        for (long t = cfg.churnIntervalMs; t < cfg.durationMs; t += cfg.churnIntervalMs) {
            net.clock.schedule(t, new Runnable() {
                @Override
                public void run() {
                    int count = (int) Math.round(cfg.churn * cfg.nodes);
                    for (int k = 0; k < count; k++) {
                        final int id = r.nextInt(cfg.nodes);
                        if (!net.info(id).alive) continue;
                        net.killNode(id);
                        net.clock.schedule(5_000 + r.nextInt(15_000), new Runnable() {
                            @Override public void run() { net.reviveNode(id); }
                        });
                    }
                }
            });
        }

        // Gateway failure burst at the midpoint.
        net.clock.schedule(cfg.durationMs / 2, new Runnable() {
            @Override
            public void run() {
                List<Integer> all = net.allGateways();
                Collections.shuffle(all, r);
                int kill = (int) Math.round(cfg.gatewayFail * all.size());
                for (int i = 0; i < kill; i++) net.killNode(all.get(i));
            }
        });

        // Traffic: conversations. A pair talks back and forth, so replies and follow-ups can use learned routes.
        final int convLen = Math.max(1, cfg.conversationLength);
        int conversations = Math.max(1, cfg.messages / convLen);
        for (int i = 0; i < conversations; i++) {
            long t = cfg.warmupMs + (long) (r.nextDouble() * (cfg.durationMs - cfg.warmupMs) * 0.8);
            net.clock.schedule(t, new Runnable() {
                @Override
                public void run() {
                    final int a = pickAlive(net, r, -1, -1);
                    if (a < 0) return;
                    int b;
                    if (r.nextDouble() < cfg.localRatio) {
                        int z = net.zoneOf(a);
                        int start = z * cfg.zoneSize;
                        int end = (z == net.zoneCount - 1) ? cfg.nodes : start + cfg.zoneSize;
                        b = pickAlive(net, r, start, end);
                    } else {
                        b = pickAlive(net, r, -1, -1);
                    }
                    if (b < 0 || b == a) return;
                    final int peer = b;
                    long at = 0;
                    for (int k = 0; k < convLen; k++) {
                        final boolean forward = k % 2 == 0;
                        net.clock.schedule(at, new Runnable() {
                            @Override
                            public void run() {
                                int src = forward ? a : peer;
                                int dst = forward ? peer : a;
                                if (!net.info(src).alive || !net.info(dst).alive) return;
                                byte[] payload = new byte[32];
                                r.nextBytes(payload);
                                net.sendMessage(src, dst, payload, 1 + r.nextInt(3));
                            }
                        });
                        at += 1_000 + r.nextInt(7_000);
                    }
                }
            });
        }

        net.clock.runUntil(cfg.durationMs + cfg.drainMs);
        return net.metrics;
    }

    private static int pickAlive(SimNetwork net, Random r, int from, int to) {
        for (int tries = 0; tries < 50; tries++) {
            int id = from < 0 ? r.nextInt(net.n) : from + r.nextInt(to - from);
            NodeInfo info = net.info(id);
            if (info.alive) return id;
        }
        return -1;
    }
}
