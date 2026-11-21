package com.herabarman.meshnet.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One node of the mesh. Pure logic: no sockets, no Android, no threads.
 *
 * Routing rules:
 *  1. Originator or gateway with internet, and the server knows the destination: server path.
 *  2. Destination in my zone: unicast if a learned route exists, otherwise flood inside the zone.
 *  3. Destination in another zone: gateways forward over the backbone (greedy on the zone ring),
 *     ordinary nodes unicast toward the gateway if they know the way, otherwise flood the zone.
 *  4. No backbone route: store the packet and retry later.
 *
 * Route learning: the neighbour a packet first arrived from is the way back to its source.
 * One flood therefore teaches the whole zone how to reach the sender, and replies cost a few hops.
 *
 * ACK + fallback: the destination answers with a tiny ACK. If none arrives in time the sender drops
 * its learned route and re-sends by flooding (attempt 1, 2, ...), which no stale route can block.
 */
public final class MeshNode implements Transport.Listener {
    /** fromPeer value for packets that arrived through the server. */
    public static final int SERVER_PEER = -1;
    /** "from" value for packets this node originates (or re-routes itself). */
    private static final int LOCAL = -2;
    /** Route cache keys for "the way toward zone z" are ZONE_KEY_BASE - z (always negative). */
    private static final int ZONE_KEY_BASE = -10;
    public static final long ACK_MASK = 0x5851F42D4C957F2DL;
    private static final long ATTEMPT_MIX = 0x9E3779B97F4A7C15L;

    public interface Listener {
        void onDelivered(MeshNode node, Packet packet, byte[] plain, int fromPeer);
        void onDuplicate(MeshNode node, Packet packet);
        void onDropped(MeshNode node, Packet packet, String reason);
        /** A relay decided not to forward a flooded packet (counter-based suppression). */
        default void onFloodSuppressed(MeshNode node, Packet packet) {}
        /** The destination confirmed one of my messages. */
        default void onAcked(MeshNode node, long msgId) {}
        /** No ACK in time: I am re-sending by flood. */
        default void onRetry(MeshNode node, Packet packet) {}
    }

    /** A relayed packet waiting for its forward timer. */
    private static final class Pending {
        final Packet packet;
        final List<Integer> heardFrom = new ArrayList<>(4);

        Pending(Packet packet) {
            this.packet = packet;
        }
    }

    /** A message I sent and have not seen an ACK for yet. */
    private static final class Outgoing {
        final Packet packet;
        int attempt;

        Outgoing(Packet packet) {
            this.packet = packet;
        }
    }

    private final int id;
    private final NodeInfo info;
    private final MeshConfig cfg;
    private final Transport transport;
    private final Clock clock;
    private final ZoneRouter.ZoneMap zones;
    private final Crypto crypto;
    private final ServerBridge server;
    private final Random rng;
    private final DedupCache dedup;
    private final StoreAndForward store;
    private final StorageAdapter storage;
    private final List<Integer> zonePeers = new ArrayList<>(6);
    private final List<Integer> backbonePeers = new ArrayList<>(2);
    private Listener listener;
    private boolean gateway;
    private boolean retryScheduled;
    private Map<Long, Pending> pending;
    private Map<Long, Outgoing> outgoing;
    private RouteCache routes;
    private DedupCache delivered;

    public MeshNode(NodeInfo info, MeshConfig cfg, Transport transport, Clock clock,
                    ZoneRouter.ZoneMap zones, Crypto crypto, ServerBridge server, Random rng) {
        this(info, cfg, transport, clock, zones, crypto, server, rng, null);
    }

    /** @param storage where queued and unconfirmed messages are kept across restarts (null = RAM only) */
    public MeshNode(NodeInfo info, MeshConfig cfg, Transport transport, Clock clock,
                    ZoneRouter.ZoneMap zones, Crypto crypto, ServerBridge server, Random rng,
                    StorageAdapter storage) {
        this.storage = storage;
        this.id = info.id;
        this.info = info;
        this.cfg = cfg;
        this.transport = transport;
        this.clock = clock;
        this.zones = zones;
        this.crypto = crypto;
        this.server = server;
        this.rng = rng;
        this.dedup = new DedupCache(cfg.dedupMaxEntries, cfg.dedupTtlMs);
        this.store = new StoreAndForward(cfg.storeMaxPackets, storage);
        transport.setListener(this);
    }

    public int id() { return id; }
    public boolean isGateway() { return gateway; }
    public void setGateway(boolean gateway) { this.gateway = gateway; }
    public void setListener(Listener listener) { this.listener = listener; }
    public int storedCount() { return store.size(); }

    /** Learned next hop toward dst (or toward dst's zone if it is another zone), -1 if none. For diagnostics. */
    public int routeTo(int dst) {
        return routes == null ? -1 : routes.get(routeKeyFor(dst), clock.nowMs());
    }

    private int routeKeyFor(int dst) {
        int z = zones.zoneOf(dst);
        return z == info.zone ? dst : ZONE_KEY_BASE - z;
    }

    private static long dedupKey(Packet p) {
        return p.msgId + p.attempt * ATTEMPT_MIX;
    }

    // ---- persistence ------------------------------------------------------------------------

    private static String outboxKey(long ackId) {
        return "out-" + String.format("%016x", ackId);
    }

    private void saveOutgoing(long ackId, Outgoing o) {
        if (storage == null) return;
        byte[] pk = o.packet.encode();
        byte[] raw = new byte[1 + pk.length];
        raw[0] = (byte) o.attempt;
        System.arraycopy(pk, 0, raw, 1, pk.length);
        storage.put(outboxKey(ackId), raw);
    }

    private void forgetOutgoing(long ackId) {
        if (storage != null) storage.remove(outboxKey(ackId));
    }

    /**
     * Call once after construction when a StorageAdapter is used. Brings back the queued packets and
     * the messages that were sent but never confirmed; the latter are re-sent at once as the next attempt.
     * @return number of restored queued packets + unconfirmed messages
     */
    public int restore() {
        if (storage == null) return 0;
        int count = store.restore(clock.nowMs());
        if (store.size() > 0) scheduleRetry();
        if (cfg.ackEnabled) {
            for (String key : storage.keys("out-")) {
                byte[] raw = storage.get(key);
                try {
                    if (raw == null || raw.length < 1 + Packet.HEADER_SIZE) throw new IllegalArgumentException("short");
                    byte[] pk = new byte[raw.length - 1];
                    System.arraycopy(raw, 1, pk, 0, pk.length);
                    Packet p = Packet.decode(pk);
                    if (p.src != id) throw new IllegalArgumentException("not mine");
                    Outgoing o = new Outgoing(p);
                    o.attempt = raw[0] & 0xFF;
                    if (outgoing == null) outgoing = new HashMap<>();
                    final long ackId = p.msgId ^ ACK_MASK;
                    outgoing.put(ackId, o);
                    clock.schedule(1, new Runnable() {
                        @Override public void run() { onAckTimeout(ackId); }
                    });
                    count++;
                } catch (IllegalArgumentException e) {
                    storage.remove(key);
                }
            }
        }
        return count;
    }

    // ---- sending ----------------------------------------------------------------------------

    /** Encrypt and send a message. Returns the message id. */
    public long send(int dst, byte[] plain, int priority) {
        long msgId = rng.nextLong();
        byte[] body = crypto.encrypt(plain, id, dst, msgId);
        Packet p = new Packet(Packet.TYPE_DATA, cfg.defaultTtl, priority, msgId, id, dst, body);
        dedup.firstSeen(dedupKey(p), clock.nowMs()); // so echoes of my own packet are dropped
        if (cfg.ackEnabled) {
            if (outgoing == null) outgoing = new HashMap<>();
            long ackId = msgId ^ ACK_MASK;
            Outgoing o = new Outgoing(p);
            outgoing.put(ackId, o);
            saveOutgoing(ackId, o);
            scheduleAckTimeout(ackId, 0);
        }
        route(p, LOCAL);
        return msgId;
    }

    private void scheduleAckTimeout(final long ackId, int attempt) {
        clock.schedule(cfg.ackTimeoutMs << attempt, new Runnable() {
            @Override
            public void run() {
                onAckTimeout(ackId);
            }
        });
    }

    private void onAckTimeout(long ackId) {
        Outgoing o = outgoing == null ? null : outgoing.get(ackId);
        if (o == null) return; // ACK arrived
        if (o.attempt >= cfg.maxRetries) {
            outgoing.remove(ackId);
            forgetOutgoing(ackId);
            if (listener != null) listener.onDropped(this, o.packet, "no-ack");
            return;
        }
        o.attempt++;
        saveOutgoing(ackId, o);
        if (routes != null) routes.remove(routeKeyFor(o.packet.dst)); // that route did not work
        Packet retry = o.packet.withAttempt(o.attempt);
        dedup.firstSeen(dedupKey(retry), clock.nowMs());
        if (listener != null) listener.onRetry(this, retry);
        route(retry, LOCAL);
        scheduleAckTimeout(ackId, o.attempt);
    }

    private void sendAck(Packet data) {
        Packet ack = new Packet(Packet.TYPE_ACK, cfg.defaultTtl, 255, data.msgId ^ ACK_MASK,
                id, data.src, new byte[0], data.attempt);
        dedup.firstSeen(dedupKey(ack), clock.nowMs());
        route(ack, LOCAL);
    }

    // ---- receiving --------------------------------------------------------------------------

    @Override
    public void onReceive(int fromPeer, byte[] data) {
        Packet p;
        try {
            p = Packet.decode(data);
        } catch (IllegalArgumentException e) {
            if (listener != null) listener.onDropped(this, null, "malformed");
            return;
        }
        if (!dedup.firstSeen(dedupKey(p), clock.nowMs())) {
            // A neighbour that sends me a packet obviously has it: remember that while my forward timer runs.
            Pending waiting = pending == null ? null : pending.get(dedupKey(p));
            if (waiting != null && fromPeer >= 0 && !waiting.heardFrom.contains(fromPeer)) {
                waiting.heardFrom.add(fromPeer);
            }
            if (listener != null) listener.onDuplicate(this, p);
            return;
        }
        learnRoute(p, fromPeer);
        if (p.dst == id) {
            handleLocal(p, fromPeer);
            return;
        }
        if (p.ttl <= 1) {
            if (listener != null) listener.onDropped(this, p, "ttl");
            return;
        }
        route(p.withTtl(p.ttl - 1), fromPeer);
    }

    private void handleLocal(Packet p, int fromPeer) {
        if (p.type == Packet.TYPE_ACK) {
            Outgoing o = outgoing == null ? null : outgoing.remove(p.msgId);
            if (o != null) forgetOutgoing(p.msgId);
            if (o != null && listener != null) listener.onAcked(this, o.packet.msgId);
            return;
        }
        if (delivered == null) delivered = new DedupCache(cfg.dedupMaxEntries, cfg.dedupTtlMs * 4);
        if (delivered.firstSeen(p.msgId, clock.nowMs())) {
            byte[] plain = crypto.decrypt(p.payload, p.src, p.dst, p.msgId);
            if (plain == null) {
                if (listener != null) listener.onDropped(this, p, "auth");
                return; // never confirm something that failed authentication
            }
            if (listener != null) listener.onDelivered(this, p, plain, fromPeer);
        } else if (listener != null) {
            listener.onDuplicate(this, p); // a fallback retransmission of something I already have
        }
        if (cfg.ackEnabled) sendAck(p); // also for repeats: the earlier ACK may have been lost
    }

    /** The first copy arrives over the fastest path, so its sender-side neighbour is the way back. */
    private void learnRoute(Packet p, int from) {
        if (!cfg.routeLearning || from < 0 || p.src == id) return;
        if (!zonePeers.contains(from)) return;
        if (routes == null) routes = new RouteCache(cfg.routeCacheMax, cfg.routeTtlMs);
        int srcZone = zones.zoneOf(p.src);
        routes.put(srcZone == info.zone ? p.src : ZONE_KEY_BASE - srcZone, from, clock.nowMs());
    }

    @Override
    public void onPeerUp(int peerId) {
        List<Integer> list = zones.zoneOf(peerId) == info.zone ? zonePeers : backbonePeers;
        if (!list.contains(peerId)) list.add(peerId);
    }

    @Override
    public void onPeerDown(int peerId) {
        zonePeers.remove(Integer.valueOf(peerId));
        backbonePeers.remove(Integer.valueOf(peerId));
    }

    // ---- routing ----------------------------------------------------------------------------

    private void route(Packet p, int from) {
        boolean serverOk = cfg.deliveryMode != DeliveryMode.OFFLINE_ONLY
                && server != null && server.available() && server.isReachable(p.dst);
        boolean border = !backbonePeers.isEmpty();
        boolean mayUseServer = serverOk && (p.src == id || border || gateway);
        // ONLINE_FIRST goes straight to the server; otherwise the server only helps after an ACK timeout (attempt > 0).
        if (mayUseServer && (cfg.deliveryMode == DeliveryMode.ONLINE_FIRST || p.attempt > 0)) {
            server.send(p.dst, p.encode());
            return;
        }
        int dstZone = zones.zoneOf(p.dst);
        if (dstZone == info.zone) {
            forwardInZone(p, from);
            return;
        }
        int hop = backboneHop(dstZone);
        if (hop >= 0) {
            transport.send(hop, p.encode());
            return;
        }
        boolean fromBackbone = from >= 0 && !zonePeers.contains(from);
        boolean ownsBackbone = cfg.fullMesh ? (border && fromBackbone) : (border || gateway);
        if (ownsBackbone) {
            if (mayUseServer) {            // no mesh route right now: the server helps
                server.send(p.dst, p.encode());
            } else {
                storeForLater(p, clock.nowMs() + cfg.storeTtlMs);
            }
        } else {
            forwardInZone(p, from);        // hand it to a border node of my zone
        }
    }

    /** A direct link to the destination zone if I have one, else (ring mode) the greedy closer peer. */
    private int backboneHop(int dstZone) {
        int chosen = -1;
        int seen = 0;
        for (int peer : backbonePeers) {
            if (zones.zoneOf(peer) == dstZone && rng.nextInt(++seen) == 0) chosen = peer; // random among direct links
        }
        if (chosen >= 0 || cfg.fullMesh || backbonePeers.isEmpty()) return chosen;
        return ZoneRouter.chooseNextHop(info.zone, dstZone, cfg.zoneCount, backbonePeers, zones, peer -> true);
    }

    /** Unicast along a learned route if there is a live one, otherwise flood. Retransmissions always flood. */
    private void forwardInZone(Packet p, int from) {
        if (cfg.routeLearning && p.attempt == 0 && routes != null) {
            int next = routes.get(routeKeyFor(p.dst), clock.nowMs());
            if (next >= 0 && next != from && zonePeers.contains(next)) {
                transport.send(next, p.encode());
                return;
            }
        }
        floodZone(p, from);
    }

    /**
     * Flood inside the zone. The originator sends at once. Relays wait a random time first and then
     * skip neighbours that already sent them the packet (sender suppression), and, if enough
     * neighbours did, skip forwarding altogether (counter-based suppression).
     */
    private void floodZone(Packet p, int from) {
        if (cfg.floodJitterMs <= 0 || from == LOCAL) {
            byte[] bytes = p.encode();
            for (int peer : zonePeers) {
                if (peer != from) transport.send(peer, bytes);
            }
            return;
        }
        final Pending pd = new Pending(p);
        if (from >= 0) pd.heardFrom.add(from);
        if (pending == null) pending = new HashMap<>();
        pending.put(dedupKey(p), pd);
        clock.schedule(rng.nextInt(cfg.floodJitterMs + 1), new Runnable() {
            @Override
            public void run() {
                fireFlood(pd);
            }
        });
    }

    private void fireFlood(Pending pd) {
        pending.remove(dedupKey(pd.packet));
        // In ring mode a gateway is the only way out of the zone, so it never stays silent.
        // In full-mesh mode most nodes are border nodes with several parallel links, so none is critical.
        boolean critical = gateway && !cfg.fullMesh;
        if (!critical && cfg.counterThreshold > 0 && pd.heardFrom.size() >= cfg.counterThreshold) {
            if (listener != null) listener.onFloodSuppressed(this, pd.packet);
            return;
        }
        List<Integer> targets = new ArrayList<>(zonePeers.size());
        for (int peer : zonePeers) {
            if (!pd.heardFrom.contains(peer)) targets.add(peer);
        }
        if (!critical && cfg.floodFanout > 0 && targets.size() > cfg.floodFanout) {
            Collections.shuffle(targets, rng);
            targets = targets.subList(0, cfg.floodFanout);
        }
        if (targets.isEmpty()) return;
        byte[] bytes = pd.packet.encode();
        for (int peer : targets) transport.send(peer, bytes);
    }

    private void storeForLater(Packet p, long expiresAtMs) {
        if (store.enqueue(p, expiresAtMs, clock.nowMs())) {
            scheduleRetry();
        } else if (listener != null) {
            listener.onDropped(this, p, "store-full");
        }
    }

    private void scheduleRetry() {
        if (retryScheduled) return;
        retryScheduled = true;
        clock.schedule(cfg.retryMs, new Runnable() {
            @Override
            public void run() {
                retryScheduled = false;
                for (StoreAndForward.Item item : store.takeAll(clock.nowMs())) {
                    int dstZone = zones.zoneOf(item.packet.dst);
                    if (dstZone != info.zone && (!backbonePeers.isEmpty() || gateway)) {
                        int hop = backboneHop(dstZone);
                        if (hop >= 0) {
                            transport.send(hop, item.packet.encode());
                            store.done(item);
                        } else {
                            store.requeue(item);
                        }
                    } else {
                        route(item.packet, LOCAL); // role changed: normal routing decides
                        store.done(item);
                    }
                }
                if (store.size() > 0) scheduleRetry();
            }
        });
    }
}
