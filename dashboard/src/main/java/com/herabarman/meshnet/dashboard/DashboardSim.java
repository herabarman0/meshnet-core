package com.herabarman.meshnet.dashboard;

import com.herabarman.meshnet.core.MeshNode;
import com.herabarman.meshnet.core.NodeInfo;
import com.herabarman.meshnet.core.Packet;
import com.herabarman.meshnet.sim.Metrics;
import com.herabarman.meshnet.sim.SimConfig;
import com.herabarman.meshnet.sim.SimNetwork;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * One interactive simulation. SimNetwork is single threaded, so every public method is synchronized:
 * the ticker thread and the HTTP handlers take turns.
 */
final class DashboardSim {
    private static final int MAX_EVENTS = 6000;
    private static final int MAX_LOG = 40;

    private SimConfig cfg;
    private SimNetwork net;
    private final Random rnd = new Random(42);
    private final Set<Long> tracked = new HashSet<>();
    private final Map<Long, Integer> messageNumber = new HashMap<>();
    private final ArrayDeque<long[]> events = new ArrayDeque<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private long seq;
    private int messageCounter;
    private boolean paused;
    private double speed = 1.0;
    private boolean showAll;
    private double trafficPerMin;

    DashboardSim() {
        cfg = new SimConfig();
        cfg.nodes = 120;
        cfg.zoneSize = 12;
        cfg.gatewayCount = 2;
        cfg.onlineRatio = 0.2;
        cfg.churn = 0.0;
        cfg.jitterMs = 10;
        rebuild();
    }

    // ---- lifecycle --------------------------------------------------------------------------

    private void rebuild() {
        net = new SimNetwork(cfg);
        tracked.clear();
        messageNumber.clear();
        events.clear();
        log.clear();
        messageCounter = 0;
        net.setTracer(new SimNetwork.Tracer() {
            @Override
            public void onTransmit(long t, int from, int to, byte[] data, boolean viaServer) {
                int kind = 0;
                boolean wanted = showAll;
                try {
                    Packet p = Packet.decode(data);
                    kind = p.type == Packet.TYPE_ACK ? 1 : 0;
                    long key = p.type == Packet.TYPE_ACK ? p.msgId ^ MeshNode.ACK_MASK : p.msgId;
                    if (tracked.contains(key)) wanted = true;
                } catch (IllegalArgumentException ignored) {
                    // not decodable: only shown in "show all" mode
                }
                if (!wanted) return;
                events.addLast(new long[]{++seq, t, from, to, kind, viaServer ? 1 : 0});
                while (events.size() > MAX_EVENTS) events.removeFirst();
            }

            @Override
            public void onDelivered(long msgId, int dst, long latencyMs, int hops, boolean viaServer) {
                if (!tracked.contains(msgId)) return;
                addLog("#" + messageNumber.get(msgId) + " পৌঁছেছে (" + dst + "): " + latencyMs + "ms, "
                        + hops + " হপ, " + (viaServer ? "সার্ভার দিয়ে" : "মেশ দিয়ে"));
            }

            @Override
            public void onAcked(long msgId) {
                if (tracked.contains(msgId)) addLog("#" + messageNumber.get(msgId) + " ACK ফিরেছে");
            }

            @Override
            public void onRetry(long msgId) {
                if (tracked.contains(msgId)) addLog("#" + messageNumber.get(msgId) + " ACK আসেনি, ফ্লাডে আবার পাঠানো হচ্ছে");
            }
        });
        scheduleChurn();
        scheduleTraffic();
    }

    private void addLog(String line) {
        log.addLast(String.format(Locale.ROOT, "%.1fs  %s", net.clock.nowMs() / 1000.0, line));
        while (log.size() > MAX_LOG) log.removeFirst();
    }

    private void scheduleChurn() {
        final SimNetwork mine = net;
        net.clock.schedule(5_000, new Runnable() {
            @Override
            public void run() {
                if (mine != net) return; // network was rebuilt
                int count = (int) Math.round(cfg.churn * cfg.nodes);
                for (int k = 0; k < count; k++) {
                    final int id = rnd.nextInt(cfg.nodes);
                    if (!net.info(id).alive) continue;
                    net.killNode(id);
                    net.clock.schedule(5_000 + rnd.nextInt(15_000), new Runnable() {
                        @Override public void run() { if (mine == net) net.reviveNode(id); }
                    });
                }
                scheduleChurn();
            }
        });
    }

    private void scheduleTraffic() {
        final SimNetwork mine = net;
        net.clock.schedule(1_000, new Runnable() {
            @Override
            public void run() {
                if (mine != net) return;
                if (trafficPerMin > 0 && rnd.nextDouble() < trafficPerMin / 60.0) startConversation();
                scheduleTraffic();
            }
        });
    }

    /** Background traffic: a pair that talks back and forth (not traced unless "show all"). */
    private void startConversation() {
        final int a = randomAlive(-1, -1);
        int b = rnd.nextDouble() < 0.3 ? randomAliveInZone(net.zoneOf(Math.max(a, 0))) : randomAlive(-1, -1);
        if (a < 0 || b < 0 || a == b) return;
        final int peer = b;
        long at = 0;
        for (int k = 0; k < 4; k++) {
            final boolean forward = k % 2 == 0;
            net.clock.schedule(at, new Runnable() {
                @Override
                public void run() {
                    int src = forward ? a : peer;
                    int dst = forward ? peer : a;
                    if (net.info(src).alive && net.info(dst).alive) net.sendMessage(src, dst, new byte[24], 1);
                }
            });
            at += 1_000 + rnd.nextInt(3_000);
        }
    }

    // ---- controls ---------------------------------------------------------------------------

    synchronized void tick(long realMs) {
        if (!paused) net.clock.runFor((long) (realMs * speed));
    }

    synchronized String send(String mode, int src, int dst) {
        if (src < 0 || dst < 0) {
            int a = randomAlive(-1, -1);
            int b = -1;
            if ("local".equals(mode)) {
                b = randomAliveInZone(net.zoneOf(Math.max(a, 0)));
            } else {
                for (int tries = 0; tries < 100 && b < 0; tries++) {
                    int c = randomAlive(-1, -1);
                    if (c >= 0 && net.zoneOf(c) != net.zoneOf(Math.max(a, 0))) b = c;
                }
            }
            src = a;
            dst = b;
        }
        if (src < 0 || dst < 0 || src == dst || src >= cfg.nodes || dst >= cfg.nodes) return "{\"ok\":false,\"error\":\"no valid pair\"}";
        if (!net.info(src).alive) return "{\"ok\":false,\"error\":\"source is down\"}";
        int number = ++messageCounter;
        long msgId = net.sendMessage(src, dst, new byte[24], 2);
        tracked.add(msgId);
        messageNumber.put(msgId, number);
        addLog("#" + number + " " + src + " → " + dst + " পাঠানো হয়েছে");
        return "{\"ok\":true,\"src\":" + src + ",\"dst\":" + dst + ",\"id\":" + number + "}";
    }

    synchronized void kill(int id) { if (id >= 0 && id < cfg.nodes) { net.killNode(id); addLog("নোড " + id + " বন্ধ"); } }
    synchronized void revive(int id) { if (id >= 0 && id < cfg.nodes) { net.reviveNode(id); addLog("নোড " + id + " চালু"); } }

    /** Ring mode: all gateways of the zone. Full mesh: two of its border nodes (almost every node is one). */
    synchronized void killGateways(int zone) {
        if (zone < 0 || zone >= net.zoneCount) return;
        List<Integer> victims = net.gatewaysOf(zone);
        int limit = net.isFullMesh() ? Math.max(1, cfg.gatewayCount) : victims.size();
        for (int i = 0; i < Math.min(limit, victims.size()); i++) net.killNode(victims.get(i));
        addLog("জোন " + zone + "-এর " + Math.min(limit, victims.size()) + "টা বর্ডার নোড বন্ধ");
    }

    /** Live settings apply at once, structural ones rebuild the network. */
    synchronized void configure(Map<String, String> q) {
        boolean rebuild = "1".equals(q.get("reset"));
        int nodes = clamp(intOf(q, "nodes", cfg.nodes), 20, 2000);
        int zoneSize = clamp(intOf(q, "zoneSize", cfg.zoneSize), 4, 40);
        int gateways = clamp(intOf(q, "gateways", cfg.gatewayCount), 1, 3);
        double online = clamp(intOf(q, "online", (int) Math.round(cfg.onlineRatio * 100)), 0, 100) / 100.0;
        boolean routes = boolOf(q, "routes", cfg.routeLearning);
        boolean ack = boolOf(q, "ack", cfg.ackEnabled);
        boolean suppress = boolOf(q, "suppress", cfg.floodJitterMs > 0);
        String backbone = q.containsKey("backbone") ? q.get("backbone") : cfg.backbone;
        if (!"full".equals(backbone) && !"chord".equals(backbone)) backbone = "auto";
        int links = clamp(intOf(q, "links", cfg.linksPerPair), 1, 4);
        boolean serverFirst = boolOf(q, "serverFirst", cfg.serverFirst);
        if (!backbone.equals(cfg.backbone) || links != cfg.linksPerPair || serverFirst != cfg.serverFirst
                || nodes != cfg.nodes || zoneSize != cfg.zoneSize || gateways != cfg.gatewayCount
                || Math.abs(online - cfg.onlineRatio) > 1e-9 || routes != cfg.routeLearning
                || ack != cfg.ackEnabled || suppress != (cfg.floodJitterMs > 0)) {
            rebuild = true;
        }
        SimConfig next = rebuild ? new SimConfig() : cfg;
        next.nodes = nodes;
        next.zoneSize = zoneSize;
        next.gatewayCount = gateways;
        next.onlineRatio = online;
        next.routeLearning = routes;
        next.ackEnabled = ack;
        next.backbone = backbone;
        next.linksPerPair = links;
        next.serverFirst = serverFirst;
        next.floodJitterMs = suppress ? 40 : 0;
        next.counterThreshold = suppress ? 3 : 0;
        next.jitterMs = 10;
        next.churn = clamp(intOf(q, "churn", (int) Math.round(cfg.churn * 100)), 0, 20) / 100.0;
        next.lossRate = clamp(intOf(q, "loss", (int) Math.round(cfg.lossRate * 100)), 0, 60) / 100.0;
        trafficPerMin = clamp(intOf(q, "traffic", (int) trafficPerMin), 0, 120);
        speed = Math.max(0.05, Math.min(50, doubleOf(q, "speed", speed)));
        paused = boolOf(q, "paused", paused);
        showAll = boolOf(q, "showAll", showAll);
        cfg = next;
        if (rebuild) rebuild();
    }

    // ---- state ------------------------------------------------------------------------------

    synchronized String state(long since, int a, int b) {
        StringBuilder sb = new StringBuilder(32_000);
        Metrics m = net.metrics;
        sb.append("{\"t\":").append(net.clock.nowMs())
                .append(",\"n\":").append(cfg.nodes)
                .append(",\"zoneSize\":").append(cfg.zoneSize)
                .append(",\"zoneCount\":").append(net.zoneCount)
                .append(",\"speed\":").append(speed)
                .append(",\"paused\":").append(paused)
                .append(",\"showAll\":").append(showAll)
                .append(",\"seq\":").append(seq)
                .append(",\"settings\":{\"gateways\":").append(cfg.gatewayCount)
                .append(",\"online\":").append((int) Math.round(cfg.onlineRatio * 100))
                .append(",\"churn\":").append((int) Math.round(cfg.churn * 100))
                .append(",\"loss\":").append((int) Math.round(cfg.lossRate * 100))
                .append(",\"traffic\":").append((int) trafficPerMin)
                .append(",\"routes\":").append(cfg.routeLearning)
                .append(",\"ack\":").append(cfg.ackEnabled)
                .append(",\"suppress\":").append(cfg.floodJitterMs > 0)
                .append(",\"backbone\":\"").append(net.isFullMesh() ? "full" : "chord").append('"')
                .append(",\"links\":").append(cfg.linksPerPair)
                .append(",\"serverFirst\":").append(cfg.serverFirst).append('}');

        sb.append(",\"st\":\"");
        for (int i = 0; i < cfg.nodes; i++) {
            NodeInfo info = net.info(i);
            int f = (info.alive ? 1 : 0) | (info.online ? 2 : 0) | (net.node(i).isGateway() ? 4 : 0);
            sb.append((char) ('0' + f));
        }
        sb.append('"');

        StringBuilder intra = new StringBuilder();
        StringBuilder back = new StringBuilder();
        for (int[] e : net.edges()) {
            if (!net.info(e[0]).alive || !net.info(e[1]).alive) continue;
            StringBuilder target = net.zoneOf(e[0]) == net.zoneOf(e[1]) ? intra : back;
            if (target.length() > 0) target.append(',');
            target.append(e[0]).append(',').append(e[1]);
        }
        sb.append(",\"intra\":[").append(intra).append("],\"bb\":[").append(back).append(']');

        sb.append(",\"m\":{\"sent\":").append(m.sent)
                .append(",\"delivered\":").append(m.delivered)
                .append(",\"acked\":").append(m.acked)
                .append(",\"retries\":").append(m.retries)
                .append(",\"unacked\":").append(m.unacked)
                .append(",\"suppressed\":").append(m.suppressed)
                .append(",\"viaServer\":").append(m.viaServer)
                .append(",\"dropped\":").append(m.dropped)
                .append(String.format(Locale.ROOT,
                        ",\"deliveryRate\":%.4f,\"avgHops\":%.2f,\"avgLatency\":%.0f,\"p95\":%d,\"txPerMsg\":%.1f,\"dupRatio\":%.4f}",
                        m.deliveryRate(), m.avgHops(), m.avgLatencyMs(), m.p95LatencyMs(),
                        m.transmissionsPerMessage(), m.duplicateRatio()));

        sb.append(",\"events\":[");
        boolean first = true;
        for (long[] e : events) {
            if (e[0] <= since) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(e[0]).append(',').append(e[1]).append(',').append(e[2]).append(',')
                    .append(e[3]).append(',').append(e[4]).append(',').append(e[5]).append(']');
        }
        sb.append(']');

        sb.append(",\"log\":[");
        first = true;
        for (String line : log) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(line)).append('"');
        }
        sb.append(']');

        sb.append(",\"route\":[");
        if (a >= 0 && b >= 0 && a < cfg.nodes && b < cfg.nodes && a != b) {
            int cur = a;
            Set<Integer> seen = new HashSet<>();
            sb.append(cur);
            while (cur != b && seen.add(cur) && seen.size() < 40) {
                int next = net.node(cur).routeTo(b);
                if (next < 0) break;
                sb.append(',').append(next);
                cur = next;
            }
        }
        sb.append(']');

        if (a >= 0 && a < cfg.nodes) {
            NodeInfo ia = net.info(a);
            sb.append(",\"selA\":{\"id\":").append(a).append(",\"zone\":").append(net.zoneOf(a))
                    .append(",\"alive\":").append(ia.alive).append(",\"online\":").append(ia.online)
                    .append(",\"gateway\":").append(net.node(a).isGateway())
                    .append(String.format(Locale.ROOT, ",\"battery\":%.2f,\"signal\":%.2f}", ia.battery, ia.signal));
        }
        sb.append('}');
        return sb.toString();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private int randomAlive(int from, int to) {
        for (int tries = 0; tries < 60; tries++) {
            int id = from < 0 ? rnd.nextInt(cfg.nodes) : from + rnd.nextInt(Math.max(1, to - from));
            if (net.info(id).alive) return id;
        }
        return -1;
    }

    private int randomAliveInZone(int zone) {
        int start = zone * cfg.zoneSize;
        int end = zone == net.zoneCount - 1 ? cfg.nodes : start + cfg.zoneSize;
        return randomAlive(start, end);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int intOf(Map<String, String> q, String k, int def) {
        try { return q.containsKey(k) ? Integer.parseInt(q.get(k)) : def; } catch (NumberFormatException e) { return def; }
    }

    private static double doubleOf(Map<String, String> q, String k, double def) {
        try { return q.containsKey(k) ? Double.parseDouble(q.get(k)) : def; } catch (NumberFormatException e) { return def; }
    }

    private static boolean boolOf(Map<String, String> q, String k, boolean def) {
        return q.containsKey(k) ? "1".equals(q.get(k)) || "true".equals(q.get(k)) : def;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    synchronized List<Integer> gatewaysOfZone(int z) { return net.gatewaysOf(z); }
}
