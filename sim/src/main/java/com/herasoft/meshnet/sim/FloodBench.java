package com.herasoft.meshnet.sim;

import java.util.Locale;

/**
 * Compares in-zone flooding variants on the same network, averaged over a few seeds.
 * Usage: FloodBench [nodes] [seeds]
 */
public final class FloodBench {
    private static final class Variant {
        final String name;
        final int jitter;
        final int counter;
        final int fanout;

        Variant(String name, int jitter, int counter, int fanout) {
            this.name = name;
            this.jitter = jitter;
            this.counter = counter;
            this.fanout = fanout;
        }
    }

    public static void main(String[] args) {
        int nodes = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        Variant[] variants = {
            new Variant("classic flood        ", 0, 0, 0),
            new Variant("sender-suppress only ", 40, 0, 0),
            new Variant("+ counter 4          ", 40, 4, 0),
            new Variant("+ counter 3 (default)", 40, 3, 0),
            new Variant("+ counter 2          ", 40, 2, 0),
            new Variant("+ counter 3, fanout 2", 40, 3, 2),
            new Variant("jitter 80 + counter 3", 80, 3, 0),
        };
        System.out.printf(Locale.ROOT, "nodes=%d, %d seeds, 20%% online, 1%% churn, 5%% gateway failure%n%n", nodes, seeds);
        System.out.println("variant                | delivery | tx/msg | dup ratio | avg latency | p95 latency | avg hops");
        for (Variant v : variants) {
            double del = 0, tx = 0, dup = 0, lat = 0, p95 = 0, hops = 0;
            for (int s = 1; s <= seeds; s++) {
                SimConfig c = new SimConfig();
                c.nodes = nodes;
                c.messages = 2000;
                c.seed = s;
                c.floodJitterMs = v.jitter;
                c.counterThreshold = v.counter;
                c.floodFanout = v.fanout;
                Metrics m = Simulation.run(c);
                del += m.deliveryRate();
                tx += m.transmissionsPerMessage();
                dup += m.duplicateRatio();
                lat += m.avgLatencyMs();
                p95 += m.p95LatencyMs();
                hops += m.avgHops();
            }
            System.out.printf(Locale.ROOT, "%s | %7.2f%% | %6.1f | %8.1f%% | %8.0f ms | %8.0f ms | %.2f%n",
                    v.name, del / seeds * 100, tx / seeds, dup / seeds * 100, lat / seeds, p95 / seeds, hops / seeds);
        }
    }
}
