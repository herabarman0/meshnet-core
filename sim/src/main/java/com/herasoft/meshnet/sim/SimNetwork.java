package com.herasoft.meshnet.sim;

import com.herasoft.meshnet.core.AesGcmCrypto;
import com.herasoft.meshnet.core.Crypto;
import com.herasoft.meshnet.core.GatewayManager;
import com.herasoft.meshnet.core.MeshConfig;
import com.herasoft.meshnet.core.MeshNode;
import com.herasoft.meshnet.core.NoopCrypto;
import com.herasoft.meshnet.core.NodeInfo;
import com.herasoft.meshnet.core.Packet;
import com.herasoft.meshnet.core.ServerBridge;
import com.herasoft.meshnet.core.Transport;
import com.herasoft.meshnet.core.ZoneRouter;
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
    private final Map<Long, Long> sentAt = new HashMap<>();

    @SuppressWarnings("unchecked")
    public SimNetwork(SimConfig cfg) {
        if (cfg.zoneSize < 2) throw new IllegalArgumentException("zoneSize must be >= 2");
        this.cfg = cfg;
        this.n = cfg.nodes;
        this.zoneCount = Math.max(1, n / cfg.zoneSize);
        this.rng = new Random(cfg.seed);
        this.meshCfg.zoneCount = zoneCount;
        this.meshCfg.floodJitterMs = cfg.floodJitterMs;
        this.meshCfg.counterThreshold = cfg.counterThreshold;
        this.meshCfg.floodFanout = cfg.floodFanout;
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
            }

            @Override
            public void onDuplicate(MeshNode node, Packet p) {
                metrics.duplicates++;
            }

            @Override
            public void onDropped(MeshNode node, Packet p, String reason) {
                metrics.dropped++;
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
                @Override public void send(int dstNode, byte[] data) { serverSend(dstNode, data); }
            };
            nodes[i] = new MeshNode(infos[i], meshCfg, t, clock, this, crypto, bridge, rng);
            nodes[i].setListener(hooks);
        }
        for (int z = 0; z < zoneCount; z++) gateways.add(new ArrayList<Integer>());
        buildIntraZoneLinks();
        electInitialGateways();
        for (int z = 0; z < zoneCount; z++) rebuildBackboneFor(z);
    }

    // ---- addressing -------------------------------------------------------------------------

    @Override
    public int zoneOf(int id) {
        return Math.min(id / cfg.zoneSize, zoneCount - 1);
    }

    private int zoneStart(int z) { return z * cfg.zoneSize; }
    private int zoneEnd(int z) { return z == zoneCount - 1 ? n : (z + 1) * cfg.zoneSize; }

    public NodeInfo info(int id) { return infos[id]; }
    public MeshNode node(int id) { return nodes[id]; }
    public List<Integer> gatewaysOf(int zone) { return new ArrayList<>(gateways.get(zone)); }

    // ---- topology ---------------------------------------------------------------------------

    private void connect(int a, int b) {
        if (a == b) return;
        links[a].add(b);
        links[b].add(a);
        nodes[a].onPeerUp(b);
        nodes[b].onPeerUp(a);
    }

    private void disconnect(int a, int b) {
        links[a].remove(b);
        links[b].remove(a);
        nodes[a].onPeerDown(b);
        nodes[b].onPeerDown(a);
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
        if (nodes[id].isGateway()) scheduleElection(zoneOf(id));
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

    private void serverSend(final int dst, final byte[] data) {
        metrics.transmissions++;
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
        return id;
    }

    public List<Integer> allGateways() {
        List<Integer> out = new ArrayList<>();
        for (List<Integer> g : gateways) out.addAll(g);
        return out;
    }

    public Set<Integer> linksOf(int id) { return new HashSet<>(links[id]); }
}
