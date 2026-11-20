package com.herabarman.meshnet.sim;

import java.util.Locale;

/**
 * Compares flooding / routing variants on the same network, averaged over a few seeds.
 * Usage: FloodBench [nodes] [seeds]
 * Runs once with one-shot traffic (every message a new pair) and once with 6-message conversations.
 */
public final class FloodBench {
    private static final class Variant {
        final String name;
        final int jitter;
        final int counter;
        final boolean routes;
        final boolean ack;

        Variant(String name, int jitter, int counter, boolean routes, boolean ack) {
            this.name = name;
            this.jitter = jitter;
            this.counter = counter;
            this.routes = routes;
            this.ack = ack;
        }
    }

    public static void main(String[] args) {
        int nodes = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        Variant[] variants = {
            new Variant("classic flood          ", 0, 0, false, false),
            new Variant("suppression (v1)       ", 40, 3, false, false),
            new Variant("suppression + ACK only ", 40, 3, false, true),
            new Variant("routes, no ACK         ", 40, 3, true, false),
            new Variant("routes + ACK (default) ", 40, 3, true, true),
        };
        for (int conv : new int[]{1, 6}) {
            System.out.printf(Locale.ROOT, "%nnodes=%d, %d seeds, %s, 20%% online, 1%% churn, 5%% gateway failure%n",
                    nodes, seeds, conv == 1 ? "one-shot traffic" : "6-message conversations");
            System.out.println("variant                  | delivery | tx/msg | avg latency | p95 latency | retries | unacked");
            for (Variant v : variants) {
                double del = 0, tx = 0, lat = 0, p95 = 0, retries = 0, unacked = 0;
                for (int s = 1; s <= seeds; s++) {
                    SimConfig c = new SimConfig();
                    c.nodes = nodes;
                    c.messages = 2000;
                    c.seed = s;
                    c.conversationLength = conv;
                    c.floodJitterMs = v.jitter;
                    c.counterThreshold = v.counter;
                    c.routeLearning = v.routes;
                    c.ackEnabled = v.ack;
                    Metrics m = Simulation.run(c);
                    del += m.deliveryRate();
                    tx += m.transmissionsPerMessage();
                    lat += m.avgLatencyMs();
                    p95 += m.p95LatencyMs();
                    retries += m.retries;
                    unacked += m.unacked;
                }
                System.out.printf(Locale.ROOT, "%s | %7.2f%% | %6.1f | %8.0f ms | %8.0f ms | %7.0f | %7.0f%n",
                        v.name, del / seeds * 100, tx / seeds, lat / seeds, p95 / seeds, retries / seeds, unacked / seeds);
            }
        }
    }
}
