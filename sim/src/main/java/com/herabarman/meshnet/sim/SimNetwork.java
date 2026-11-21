package com.herabarman.meshnet.sim;

import com.herabarman.meshnet.core.AesGcmCrypto;
import com.herabarman.meshnet.core.Crypto;
import com.herabarman.meshnet.core.GatewayManager;
import com.herabarman.meshnet.core.MeshConfig;
import com.herabarman.meshnet.core.MeshNode;
import com.herabarman.meshnet.core.NoopCrypto;
import com.herabarman.meshnet.core.NodeInfo;
import com.herabarman.meshnet.core.Packet;
import com.herabarman.meshnet.core.ServerBridge;
import com.herabarman.meshnet.core.Transport;
import com.herabarman.meshnet.core.ZoneRouter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The simulated world: nodes, links, zones, gateways, server, failures.
 *
 * Simplification: the "gateway directory" (which nodes are gateways of zone X) is read from
 * global state instead of a real gossip/DHT. Everything else runs through the real core engine.
 */
public final class SimNetwork implements ZoneRouter.ZoneMap {
    /** Observer for visualisation. All methods run on the simulation thread. */
    public interface Tracer {
        /** A packet is on the wire (viaServer: it goes node -> server -> node). */
        void onTransmit(long timeMs, int from, int to, byte[] data, boolean viaServer);
        default void onSent(long msgId, int src, int dst) {}
        default void onDelivered(long msgId, int dst, long latencyMs, int hops, boolean viaServer) {}
        default void onAcked(long msgId) {}
        default void onRetry(long msgId) {}
    }

    public final SimConfig cfg;
    public final SimClock clock = new SimClock();
    public final Metrics metrics = new Metrics();
    public final int n;
    public final int zoneCount;

    private final MeshConfig meshCfg = new MeshConfig();
    private final Random rng;
    private final GatewayManager gm = new GatewayManager();
    private final NodeInfo[] infos;
    private final MeshNode[] nodes;
    private final HashSet<Integer>[] links;
    private final List<List<Integer>> gateways = new ArrayList<>();
    private final boolean[] electionPending;
    private final boolean fullMesh;
    private final int[] bbDegree;
    private final Set<Integer> repairPending = new HashSet<>();
    private final Map<Long, Long> sentAt = new HashMap<>();
    private Tracer tracer;

    @SuppressWarnings("unchecked")
    public SimNetwork(SimConfig cfg) {
        if (cfg.zoneSize < 2) throw new IllegalArgumentException("zoneSize must be >= 2");
        this.cfg = cfg;
        this.n = cfg.nodes;
        this.zoneCount = Math.max(1, n / cfg.zoneSize);
        this.rng = new Random(cfg.seed);
        String mode = cfg.backbone == null ? "auto" : cfg.backbone;
        this.fullMesh = zoneCount > 1 && ("full".equals(mode) || ("auto".equals(mode) && zoneCount <= cfg.fullMeshMaxZones));
        this.bbDegree = new int[n];
        this.meshCfg.fullMesh = fullMesh;
        this.meshCfg.serverFirst = cfg.serverFirst;
        this.meshCfg.zoneCount = zoneCount;
        this.meshCfg.floodJitterMs = cfg.floodJitterMs;
        this.meshCfg.counterThreshold = cfg.counterThreshold;
        this.meshCfg.floodFanout = cfg.floodFanout;
        this.meshCfg.routeLearning = cfg.routeLearning;
        this.meshCfg.ackEnabled = cfg.ackEnabled;
        this.meshCfg.ackTimeoutMs = cfg.ackTimeoutMs;
        this.meshCfg.maxRetries = cfg.maxRetries;
        this.infos = new NodeInfo[n];
        this.nodes = new MeshNode[n];
        this.links = (HashSet<Integer>[]) new HashSet[n];
        this.electionPending = new boolean[zoneCount];

        Crypto crypto = new NoopCrypto();
        if (cfg.encrypt) {
            byte[] key = new byte[32];
            rng.nextBytes(key);
            crypto = new AesGcmCrypto(key);
        }
        MeshNode.Listener hooks = new MeshNode.Listener() {
            @Override
            public void onDelivered(MeshNode node, Packet p, byte[] plain, int fromPeer) {
                Long t0 = sentAt.remove(p.msgId);
                if (t0 == null) return;
                metrics.delivered++;
                metrics.hopsSum += meshCfg.defaultTtl - p.ttl + 1;
                long lat = clock.nowMs() - t0;
                metrics.latencySum += lat;
                metrics.latencies.add(lat);
                if (fromPeer == MeshNode.SERVER_PEER) metrics.viaServer++;
                if (tracer != null) tracer.onDelivered(p.msgId, node.id(), lat,
                        meshCfg.defaultTtl - p.ttl + 1, fromPeer == MeshNode.SERVER_PEER);
            }

            @Override
            public void onDuplicate(MeshNode node, Packet p) {
                metrics.duplicates++;
            }

            @Override
            public void onDropped(MeshNode node, Packet p, String reason) {
                if ("no-ack".equals(reason)) metrics.unacked++;
                else metrics.dropped++;
            }

            @Override
            public void onAcked(MeshNode node, long msgId) {
                metrics.acked++;
                if (tracer != null) tracer.onAcked(msgId);
            }

            @Override
            public void onRetry(MeshNode node, Packet p) {
                metrics.retries++;
                if (tracer != null) tracer.onRetry(p.msgId);
            }

            @Override
            public void onFloodSuppressed(MeshNode node, Packet p) {
                metrics.suppressed++;
            }
        };
        for (int i = 0; i < n; i++) {
            int z = zoneOf(i);
            infos[i] = new NodeInfo(i, z, 0.2 + 0.8 * rng.nextDouble(), 0.2 + 0.8 * rng.nextDouble(),
                    rng.nextDouble() < cfg.onlineRatio);
            links[i] = new HashSet<>(8);
            final int id = i;
            Transport t = new Transport() {
                @Override public void send(int peerId, byte[] data) { transmit(id, peerId, data); }
                @Override public void setListener(Listener l) { /* node registers itself; routing is direct */ }
            };
            ServerBridge bridge = new ServerBridge() {
                @Override public boolean available() { return infos[id].alive && infos[id].online; }
                @Override public boolean isReachable(int nodeId) { return infos[nodeId].alive && infos[nodeId].online; }
                @Override public void send(int dstNode, byte[] data) { serverSend(id, dstNode, data); }
            };
            nodes[i] = new MeshNode(infos[i], meshCfg, t, clock, this, crypto, bridge, rng);
            nodes[i].setListener(hooks);
        }
        for (int z = 0; z < zoneCount; z++) gateways.add(new ArrayList<Integer>());
        buildIntraZoneLinks();
        if (fullMesh) {
            buildFullMesh();
        } else {
            electInitialGateways();
            for (int z = 0; z < zoneCount; z++) rebuildBackboneFor(z);
        }
    }

    public boolean isFullMesh() { return fullMesh; }

    // ---- addressing -------------------------------------------------------------------------

    @Override
    public int zoneOf(int id) {
        return Math.min(id / cfg.zoneSize, zoneCount - 1);
    }

    private int zoneStart(int z) { return z * cfg.zoneSize; }
    private int zoneEnd(int z) { return z == zoneCount - 1 ? n : (z + 1) * cfg.zoneSize; }

    public NodeInfo info(int id) { return infos[id]; }
    public MeshNode node(int id) { return nodes[id]; }
    /** Gateways (ring mode) or border nodes, i.e. nodes with offline links to other zones (full mesh). */
    public List<Integer> gatewaysOf(int zone) {
        if (!fullMesh) return new ArrayList<>(gateways.get(zone));
        List<Integer> out = new ArrayList<>();
        for (int i = zoneStart(zone); i < zoneEnd(zone); i++) if (infos[i].alive && bbDegree[i] > 0) out.add(i);
        return out;
    }

    // ---- topology ---------------------------------------------------------------------------

    private void connect(int a, int b) {
        if (a == b) return;
        boolean added = links[a].add(b);
        links[b].add(a);
        if (added && zoneOf(a) != zoneOf(b)) {
            bbDegree[a]++;
            bbDegree[b]++;
            if (fullMesh) {
                nodes[a].setGateway(true);
                nodes[b].setGateway(true);
            }
        }
        nodes[a].onPeerUp(b);
        nodes[b].onPeerUp(a);
    }

    private void disconnect(int a, int b) {
        boolean had = links[a].remove(b);
        links[b].remove(a);
        if (had && zoneOf(a) != zoneOf(b)) {
            bbDegree[a]--;
            bbDegree[b]--;
            if (fullMesh) {
                nodes[a].setGateway(bbDegree[a] > 0);
                nodes[b].setGateway(bbDegree[b] > 0);
            }
        }
        nodes[a].onPeerDown(b);
        nodes[b].onPeerDown(a);
    }

    // ---- full zone-to-zone mesh -------------------------------------------------------------

    /** Every pair of zones gets linksPerPair offline links, each between different border nodes. */
    private void buildFullMesh() {
        for (int a = 0; a < zoneCount; a++) {
            for (int b = a + 1; b < zoneCount; b++) linkPair(a, b, cfg.linksPerPair);
        }
    }

    private void linkPair(int a, int b, int count) {
        for (int j = 0; j < count; j++) {
            int na = pickBorder(a);
            int nb = pickBorder(b);
            if (na < 0 || nb < 0) return;
            connect(na, nb);
        }
    }

    /** Alive member with the fewest offline links to other zones (best score on ties), so the load is spread. */
    private int pickBorder(int zone) {
        int best = -1;
        for (int i = zoneStart(zone); i < zoneEnd(zone); i++) {
            if (!infos[i].alive) continue;
            if (best < 0 || bbDegree[i] < bbDegree[best]
                    || (bbDegree[i] == bbDegree[best] && gm.score(infos[i]) > gm.score(infos[best]))) {
                best = i;
            }
        }
        return best;
    }

    private int liveLinks(int a, int b) {
        int count = 0;
        for (int i = zoneStart(a); i < zoneEnd(a); i++) {
            if (!infos[i].alive) continue;
            for (int nb : links[i]) if (zoneOf(nb) == b && infos[nb].alive) count++;
        }
        return count;
    }

    private void scheduleRepair(int a, int b) {
        final int lo = Math.min(a, b);
        final int hi = Math.max(a, b);
        final int key = lo * zoneCount + hi;
        if (!repairPending.add(key)) return;
        clock.schedule(cfg.electionDelayMs, new Runnable() {
            @Override
            public void run() {
                repairPending.remove(key);
                int need = cfg.linksPerPair - liveLinks(lo, hi);
                if (need > 0) linkPair(lo, hi, need);
            }
        });
    }

    /** Ring through the zone plus one random chord per node: about 3-5 links per node. */
    private void buildIntraZoneLinks() {
        for (int z = 0; z < zoneCount; z++) {
            int start = zoneStart(z);
            int size = zoneEnd(z) - start;
            for (int i = 0; i < size; i++) connect(start + i, start + (i + 1) % size);
            if (size >= 4) {
                for (int i = 0; i < size; i++) connect(start + i, start + rng.nextInt(size));
            }
        }
    }

    private List<NodeInfo> members(int z) {
        List<NodeInfo> out = new ArrayList<>();
        for (int i = zoneStart(z); i < zoneEnd(z); i++) out.add(infos[i]);
        return out;
    }

    private void electInitialGateways() {
        for (int z = 0; z < zoneCount; z++) {
            List<Integer> elected = gm.elect(members(z), cfg.gatewayCount);
            gateways.set(z, elected);
            for (int g : elected) nodes[g].setGateway(true);
        }
    }

    /** Connect this zone's gateways to the current gateways of its backbone neighbour zones. */
    private void rebuildBackboneFor(int z) {
        List<Integer> mine = gateways.get(z);
        if (mine.isEmpty()) return;
        for (int nb : ZoneRouter.backboneNeighbors(z, zoneCount, cfg.maxFanOut)) {
            List<Integer> theirs = gateways.get(nb);
            if (theirs.isEmpty()) continue;
            for (int i = 0; i < mine.size(); i++) connect(mine.get(i), theirs.get(i % theirs.size()));
            for (int j = 0; j < theirs.size(); j++) connect(theirs.get(j), mine.get(j % mine.size()));
        }
    }

    private void removeBackboneLinks(int g) {
        int z = zoneOf(g);
        for (int nb : new ArrayList<>(links[g])) {
            if (zoneOf(nb) != z) disconnect(g, nb);
        }
    }

    // ---- failures and election --------------------------------------------------------------

    public void killNode(int id) {
        if (!infos[id].alive) return;
        infos[id].alive = false;
        for (int nb : links[id]) nodes[nb].onPeerDown(id);
        if (fullMesh) {
            for (int nb : links[id]) if (zoneOf(nb) != zoneOf(id)) scheduleRepair(zoneOf(id), zoneOf(nb));
        } else if (nodes[id].isGateway()) {
            scheduleElection(zoneOf(id));
        }
    }

    public void reviveNode(int id) {
        if (infos[id].alive) return;
        infos[id].alive = true;
        for (int nb : links[id]) {
            if (infos[nb].alive) {
                nodes[id].onPeerUp(nb);
                nodes[nb].onPeerUp(id);
            } else {
                nodes[id].onPeerDown(nb);
            }
        }
        if (fullMesh) return;
        int z = zoneOf(id);
        int aliveGateways = 0;
        for (int g : gateways.get(z)) if (infos[g].alive) aliveGateways++;
        if (aliveGateways < cfg.gatewayCount) scheduleElection(z);
    }

    private void scheduleElection(final int z) {
        if (electionPending[z]) return;
        electionPending[z] = true;
        clock.schedule(cfg.electionDelayMs, new Runnable() {
            @Override
            public void run() {
                electionPending[z] = false;
                runElection(z);
            }
        });
    }

    private void runElection(int z) {
        List<Integer> old = gateways.get(z);
        List<Integer> fresh = gm.reelect(members(z), old, cfg.gatewayCount);
        for (int g : old) {
            if (!fresh.contains(g)) {
                nodes[g].setGateway(false);
                removeBackboneLinks(g);
            }
        }
        for (int g : fresh) nodes[g].setGateway(true);
        gateways.set(z, fresh);
        rebuildBackboneFor(z);
    }

    // ---- delivery ---------------------------------------------------------------------------

    private void transmit(final int from, final int to, final byte[] data) {
        metrics.transmissions++;
        if (!infos[from].alive || !infos[to].alive || !links[from].contains(to)) return;
        if (cfg.lossRate > 0 && rng.nextDouble() < cfg.lossRate) return;
        if (tracer != null) tracer.onTransmit(clock.nowMs(), from, to, data, false);
        long delay = (zoneOf(from) == zoneOf(to) ? cfg.intraMs : cfg.backboneMs) + rng.nextInt(cfg.jitterMs + 1);
        clock.schedule(delay, new Runnable() {
            @Override
            public void run() {
                if (infos[to].alive) {
                    metrics.receptions++;
                    nodes[to].onReceive(from, data);
                }
            }
        });
    }

    private void serverSend(int from, final int dst, final byte[] data) {
        metrics.transmissions++;
        if (tracer != null) tracer.onTransmit(clock.nowMs(), from, dst, data, true);
        clock.schedule(cfg.serverMs, new Runnable() {
            @Override
            public void run() {
                if (infos[dst].alive && infos[dst].online) {
                    metrics.receptions++;
                    nodes[dst].onReceive(MeshNode.SERVER_PEER, data);
                }
            }
        });
    }

    /** Send a message and track it in the metrics. */
    public long sendMessage(int src, int dst, byte[] payload, int priority) {
        long id = nodes[src].send(dst, payload, priority);
        sentAt.put(id, clock.nowMs());
        metrics.sent++;
        if (tracer != null) tracer.onSent(id, src, dst);
        return id;
    }

    public void setTracer(Tracer tracer) { this.tracer = tracer; }

    /** Every link once, as {a, b} with a < b. */
    public List<int[]> edges() {
        List<int[]> out = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            for (int b : links[a]) if (a < b) out.add(new int[]{a, b});
        }
        return out;
    }

    public List<Integer> allGateways() {
        List<Integer> out = new ArrayList<>();
        if (fullMesh) {
            for (int z = 0; z < zoneCount; z++) out.addAll(gatewaysOf(z));
        } else {
            for (List<Integer> g : gateways) out.addAll(g);
        }
        return out;
    }

    public Set<Integer> linksOf(int id) { return new HashSet<>(links[id]); }
}
