package com.herabarman.meshnet.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Counters collected during a run. */
public final class Metrics {
    public long sent;
    public long delivered;
    public long viaServer;
    public long transmissions;
    public long receptions;
    public long duplicates;
    public long dropped;
    public long suppressed;
    public long acked;
    public long retries;
    public long unacked;
    public String backbone = "";
    public long hopsSum;
    public long latencySum;
    public final List<Long> latencies = new ArrayList<>();

    public double deliveryRate() { return sent == 0 ? 0 : (double) delivered / sent; }
    public double avgHops() { return delivered == 0 ? 0 : (double) hopsSum / delivered; }
    public double avgLatencyMs() { return delivered == 0 ? 0 : (double) latencySum / delivered; }
    public double duplicateRatio() { return receptions == 0 ? 0 : (double) duplicates / receptions; }
    public double serverShare() { return delivered == 0 ? 0 : (double) viaServer / delivered; }
    public double transmissionsPerMessage() { return sent == 0 ? 0 : (double) transmissions / sent; }

    public long p95LatencyMs() {
        if (latencies.isEmpty()) return 0;
        List<Long> copy = new ArrayList<>(latencies);
        Collections.sort(copy);
        return copy.get((int) Math.min(copy.size() - 1, Math.floor(copy.size() * 0.95)));
    }

    public static String csvHeader() {
        return "nodes,zoneSize,gatewayCount,onlineRatio,churn,gatewayFail,localRatio,lossRate,seed,"
                + "sent,delivered,deliveryRate,avgHops,avgLatencyMs,p95LatencyMs,"
                + "transmissionsPerNode,transmissionsPerMessage,duplicateRatio,serverShare";
    }

    public String csvRow(SimConfig c) {
        return String.format(Locale.ROOT, "%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%.4f,%.2f,%.1f,%d,%.2f,%.1f,%.4f,%.4f",
                c.nodes, c.zoneSize, c.gatewayCount, c.onlineRatio, c.churn, c.gatewayFail, c.localRatio, c.lossRate, c.seed,
                sent, delivered, deliveryRate(), avgHops(), avgLatencyMs(), p95LatencyMs(),
                (double) transmissions / c.nodes, transmissionsPerMessage(), duplicateRatio(), serverShare());
    }

    public String summary(SimConfig c) {
        return String.format(Locale.ROOT,
                "nodes=%d zones~%d %s | sent=%d delivered=%d (%.1f%%) | hops avg=%.2f | latency avg=%.0fms p95=%dms%n"
                        + "  transmissions/node=%.2f transmissions/message=%.1f | duplicate ratio=%.1f%% | via server=%.1f%% | dropped=%d | floods suppressed=%d%n  acked=%d retries=%d unacked=%d",
                c.nodes, Math.max(1, c.nodes / c.zoneSize), backbone.isEmpty() ? "" : "[" + backbone + "]", sent, delivered, deliveryRate() * 100, avgHops(),
                avgLatencyMs(), p95LatencyMs(), (double) transmissions / c.nodes, transmissionsPerMessage(),
                duplicateRatio() * 100, serverShare() * 100, dropped, suppressed, acked, retries, unacked);
    }
}
