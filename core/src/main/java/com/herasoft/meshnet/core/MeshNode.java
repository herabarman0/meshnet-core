package com.herasoft.meshnet.core;

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
 *  2. Destination in my zone: flood inside the zone (TTL + dedup).
 *  3. Destination in another zone: gateways forward over the backbone (greedy on the zone ring),
 *     ordinary nodes flood inside the zone so a gateway picks the packet up.
 *  4. No backbone route: store the packet and retry later.
 */
public final class MeshNode implements Transport.Listener {
    /** fromPeer value for packets that arrived through the server. */
    public static final int SERVER_PEER = -1;
    /** "from" value for packets this node originates (or re-routes itself). */
    private static final int LOCAL = -2;

    public interface Listener {
        void onDelivered(MeshNode node, Packet packet, byte[] plain, int fromPeer);
        void onDuplicate(MeshNode node, Packet packet);
        void onDropped(MeshNode node, Packet packet, String reason);
        /** A relay decided not to forward a flooded packet (counter-based suppression). */
        default void onFloodSuppressed(MeshNode node, Packet packet) {}
    }

    /** A relayed packet waiting for its forward timer. */
    private static final class Pending {
        final Packet packet;
        final List<Integer> heardFrom = new ArrayList<>(4);

        Pending(Packet packet) {
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
    private final List<Integer> zonePeers = new ArrayList<>(6);
    private final List<Integer> backbonePeers = new ArrayList<>(2);
    private Listener listener;
    private boolean gateway;
    private boolean retryScheduled;
    private Map<Long, Pending> pending;

    public MeshNode(NodeInfo info, MeshConfig cfg, Transport transport, Clock clock,
                    ZoneRouter.ZoneMap zones, Crypto crypto, ServerBridge server, Random rng) {
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
        this.store = new StoreAndForward(cfg.storeMaxPackets);
        transport.setListener(this);
    }

    public int id() { return id; }
    public boolean isGateway() { return gateway; }
    public void setGateway(boolean gateway) { this.gateway = gateway; }
    public void setListener(Listener listener) { this.listener = listener; }
    public int storedCount() { return store.size(); }

    /** Encrypt and send a message. Returns the message id. */
    public long send(int dst, byte[] plain, int priority) {
        long msgId = rng.nextLong();
        byte[] body = crypto.encrypt(plain, id, dst, msgId);
        Packet p = new Packet(Packet.TYPE_DATA, cfg.defaultTtl, priority, msgId, id, dst, body);
        dedup.firstSeen(msgId, clock.nowMs()); // so echoes of my own packet are dropped
        route(p, LOCAL);
        return msgId;
    }

    @Override
    public void onReceive(int fromPeer, byte[] data) {
        Packet p;
        try {
            p = Packet.decode(data);
        } catch (IllegalArgumentException e) {
            if (listener != null) listener.onDropped(this, null, "malformed");
            return;
        }
        if (!dedup.firstSeen(p.msgId, clock.nowMs())) {
            // A neighbour that sends me a packet obviously has it: remember that while my forward timer runs.
            Pending waiting = pending == null ? null : pending.get(p.msgId);
            if (waiting != null && fromPeer >= 0 && !waiting.heardFrom.contains(fromPeer)) {
                waiting.heardFrom.add(fromPeer);
            }
            if (listener != null) listener.onDuplicate(this, p);
            return;
        }
        if (p.dst == id) {
            byte[] plain = crypto.decrypt(p.payload, p.src, p.dst, p.msgId);
            if (plain == null) {
                if (listener != null) listener.onDropped(this, p, "auth");
            } else if (listener != null) {
                listener.onDelivered(this, p, plain, fromPeer);
            }
            return;
        }
        if (p.ttl <= 1) {
            if (listener != null) listener.onDropped(this, p, "ttl");
            return;
        }
        route(p.withTtl(p.ttl - 1), fromPeer);
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

    private void route(Packet p, int from) {
        if ((p.src == id || gateway) && server != null && server.available() && server.isReachable(p.dst)) {
            server.send(p.dst, p.encode());
            return;
        }
        int dstZone = zones.zoneOf(p.dst);
        if (dstZone == info.zone) {
            floodZone(p, from);
        } else if (gateway) {
            forwardBackbone(p, dstZone);
        } else {
            floodZone(p, from);
        }
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
        pending.put(p.msgId, pd);
        clock.schedule(rng.nextInt(cfg.floodJitterMs + 1), new Runnable() {
            @Override
            public void run() {
                fireFlood(pd);
            }
        });
    }

    private void fireFlood(Pending pd) {
        pending.remove(pd.packet.msgId);
        if (!gateway && cfg.counterThreshold > 0 && pd.heardFrom.size() >= cfg.counterThreshold) {
            if (listener != null) listener.onFloodSuppressed(this, pd.packet);
            return;
        }
        List<Integer> targets = new ArrayList<>(zonePeers.size());
        for (int peer : zonePeers) {
            if (!pd.heardFrom.contains(peer)) targets.add(peer);
        }
        if (!gateway && cfg.floodFanout > 0 && targets.size() > cfg.floodFanout) {
            Collections.shuffle(targets, rng);
            targets = targets.subList(0, cfg.floodFanout);
        }
        if (targets.isEmpty()) return;
        byte[] bytes = pd.packet.encode();
        for (int peer : targets) transport.send(peer, bytes);
    }

    private void forwardBackbone(Packet p, int dstZone) {
        int next = ZoneRouter.chooseNextHop(info.zone, dstZone, cfg.zoneCount, backbonePeers, zones, peer -> true);
        if (next >= 0) {
            transport.send(next, p.encode());
        } else {
            storeForLater(p, clock.nowMs() + cfg.storeTtlMs);
        }
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
                    if (gateway && dstZone != info.zone) {
                        int next = ZoneRouter.chooseNextHop(info.zone, dstZone, cfg.zoneCount,
                                backbonePeers, zones, peer -> true);
                        if (next >= 0) transport.send(next, item.packet.encode());
                        else storeForLater(item.packet, item.expiresAtMs);
                    } else {
                        route(item.packet, LOCAL); // role changed: normal routing decides
                    }
                }
            }
        });
    }
}
