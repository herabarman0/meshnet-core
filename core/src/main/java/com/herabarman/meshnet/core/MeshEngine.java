package com.herabarman.meshnet.core;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The easy way to use the mesh in an app: plug in a Transport, call send(), get callbacks.
 * Thread-safe. Every callback runs on the engine thread, so post to your UI thread before touching views.
 *
 * <pre>
 * MeshEngine engine = MeshEngine.builder(myNodeId)
 *         .zone(myZoneId).zones(ZoneRouter.blockZones(16)).zoneCount(40)
 *         .transport(bluetoothTransport)
 *         .server(webSocketBridge)                       // optional: internet helper
 *         .mode(DeliveryMode.OFFLINE_FIRST)
 *         .storage(new FileStorage(new File(filesDir, "mesh")))   // optional: survive restarts
 *         .onMessage((from, bytes) -> show(from, bytes))
 *         .onDelivery(listener)
 *         .build();
 * engine.start();
 * long id = engine.send(otherNodeId, bytes);
 * </pre>
 *
 * Each building block also works on its own: Packet, DedupCache, RouteCache, StoreAndForward,
 * GatewayManager, ZoneRouter, AesGcmCrypto, KeyExchange, FileStorage. MeshNode is the engine without the threading.
 */
public final class MeshEngine {
    public interface MessageListener {
        void onMessage(int fromNode, byte[] payload);
    }

    public interface DeliveryListener {
        /** The destination confirmed the message. */
        void onAcked(long msgId);

        /** Gave up: "no-ack" (all retries used), "store-full", "ttl", ... */
        void onFailed(long msgId, String reason);
    }

    /** Counters since start. */
    public static final class Stats {
        public final long sent, received, acked, failed, retries;

        Stats(long sent, long received, long acked, long failed, long retries) {
            this.sent = sent;
            this.received = received;
            this.acked = acked;
            this.failed = failed;
            this.retries = retries;
        }
    }

    public static Builder builder(int selfId) {
        return new Builder(selfId);
    }

    public static final class Builder {
        private final int selfId;
        private int zone;
        private int zoneCount = 1;
        private ZoneRouter.ZoneMap zones;
        private Transport transport;
        private ServerBridge server;
        private Crypto crypto = new NoopCrypto();
        private StorageAdapter storage;
        private MeshConfig config = new MeshConfig();
        private DeliveryMode mode;
        private boolean gateway;
        private Random rng = new SecureRandom();
        private MessageListener messageListener;
        private DeliveryListener deliveryListener;

        private Builder(int selfId) {
            this.selfId = selfId;
        }

        /** The zone this node lives in. */
        public Builder zone(int zone) { this.zone = zone; return this; }
        /** Total number of zones in the network (ring size for the backbone). */
        public Builder zoneCount(int zoneCount) { this.zoneCount = zoneCount; return this; }
        /** nodeId -> zone. Default: ZoneRouter.blockZones(16). */
        public Builder zones(ZoneRouter.ZoneMap zones) { this.zones = zones; return this; }
        public Builder transport(Transport transport) { this.transport = transport; return this; }
        /** Optional internet helper. */
        public Builder server(ServerBridge server) { this.server = server; return this; }
        public Builder crypto(Crypto crypto) { this.crypto = crypto; return this; }
        /** Optional: keep queued and unconfirmed messages across restarts. */
        public Builder storage(StorageAdapter storage) { this.storage = storage; return this; }
        public Builder config(MeshConfig config) { this.config = config; return this; }
        public Builder mode(DeliveryMode mode) { this.mode = mode; return this; }
        /** Start as a border node / gateway of the zone. */
        public Builder gateway(boolean gateway) { this.gateway = gateway; return this; }
        public Builder random(Random rng) { this.rng = rng; return this; }
        public Builder onMessage(MessageListener l) { this.messageListener = l; return this; }
        public Builder onDelivery(DeliveryListener l) { this.deliveryListener = l; return this; }

        public MeshEngine build() {
            if (transport == null) throw new IllegalStateException("transport is required");
            return new MeshEngine(this);
        }
    }

    /** Hands radio callbacks to the engine thread. */
    private static final class ThreadedTransport implements Transport {
        private final Transport real;
        private final SystemClock clock;

        ThreadedTransport(Transport real, SystemClock clock) {
            this.real = real;
            this.clock = clock;
        }

        @Override
        public void send(int peerId, byte[] data) {
            real.send(peerId, data);
        }

        @Override
        public void setListener(final Transport.Listener target) {
            real.setListener(new Transport.Listener() {
                @Override public void onReceive(final int from, final byte[] data) {
                    clock.post(new Runnable() { @Override public void run() { target.onReceive(from, data); } });
                }
                @Override public void onPeerUp(final int peerId) {
                    clock.post(new Runnable() { @Override public void run() { target.onPeerUp(peerId); } });
                }
                @Override public void onPeerDown(final int peerId) {
                    clock.post(new Runnable() { @Override public void run() { target.onPeerDown(peerId); } });
                }
            });
        }
    }

    private final SystemClock clock = new SystemClock();
    private final MeshNode node;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    private MeshEngine(Builder b) {
        MeshConfig cfg = b.config;
        cfg.zoneCount = b.zoneCount;
        if (b.mode != null) cfg.deliveryMode = b.mode;
        ZoneRouter.ZoneMap zones = b.zones != null ? b.zones : ZoneRouter.blockZones(16);
        NodeInfo info = new NodeInfo(b.selfId, b.zone, 1.0, 1.0, b.server != null);
        final MessageListener ml = b.messageListener;
        final DeliveryListener dl = b.deliveryListener;
        node = new MeshNode(info, cfg, new ThreadedTransport(b.transport, clock), clock, zones, b.crypto,
                b.server, b.rng, b.storage);
        node.setGateway(b.gateway);
        node.setListener(new MeshNode.Listener() {
            @Override
            public void onDelivered(MeshNode n, Packet p, byte[] plain, int fromPeer) {
                received.incrementAndGet();
                if (ml != null) ml.onMessage(p.src, plain);
            }

            @Override public void onDuplicate(MeshNode n, Packet p) { }

            @Override
            public void onDropped(MeshNode n, Packet p, String reason) {
                if (p != null && p.src == n.id() && p.type == Packet.TYPE_DATA) {
                    failed.incrementAndGet();
                    if (dl != null) dl.onFailed(p.msgId, reason);
                }
            }

            @Override
            public void onAcked(MeshNode n, long msgId) {
                acked.incrementAndGet();
                if (dl != null) dl.onAcked(msgId);
            }

            @Override
            public void onRetry(MeshNode n, Packet p) {
                retries.incrementAndGet();
            }
        });
    }

    /** Restore persisted messages (if a StorageAdapter is set) and start retrying them. */
    public void start() {
        clock.post(new Runnable() { @Override public void run() { node.restore(); } });
    }

    /** Send a message. Returns its id; onAcked / onFailed report the outcome. */
    public long send(final int dstNode, final byte[] payload) {
        return send(dstNode, payload, 1);
    }

    /** @param priority 0..255, higher is queued first when the mesh has no route */
    public long send(final int dstNode, final byte[] payload, final int priority) {
        sent.incrementAndGet();
        return clock.call(new Callable<Long>() {
            @Override public Long call() { return node.send(dstNode, payload, priority); }
        });
    }

    public void setGateway(final boolean gateway) {
        clock.post(new Runnable() { @Override public void run() { node.setGateway(gateway); } });
    }

    public Stats stats() {
        return new Stats(sent.get(), received.get(), acked.get(), failed.get(), retries.get());
    }

    /** Stop the engine thread. Persisted messages stay on disk for the next start(). */
    public void stop() {
        clock.shutdown();
    }
}
