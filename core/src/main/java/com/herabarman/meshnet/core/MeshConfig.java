package com.herabarman.meshnet.core;

/** Tunables for one node. */
public final class MeshConfig {
    public int defaultTtl = 32;
    public long dedupTtlMs = 120_000;
    public int dedupMaxEntries = 512;
    public int storeMaxPackets = 128;
    public long storeTtlMs = 300_000;
    public long retryMs = 5_000;
    /** Number of zones in the whole network (backbone ring size). */
    public int zoneCount = 1;

    // ---- flood optimisation (inside a zone) ----
    /**
     * Relayed packets wait a random 0..floodJitterMs before being forwarded, so the node can learn
     * which neighbours already have the packet and skip them. 0 = classic immediate flooding.
     * Set it above the typical link latency (about 1.5x) or the delay learns nothing.
     */
    public int floodJitterMs = 40;
    /**
     * If this many distinct neighbours already sent me the packet while I waited, I do not
     * forward it at all. 0 = off. Gateways never suppress.
     */
    public int counterThreshold = 3;
    /** Forward to at most this many neighbours (random pick). 0 = all eligible. Gateways ignore it. */
    public int floodFanout = 0;

    // ---- reverse-path route learning + end-to-end ACK ----
    /**
     * Nodes remember "to reach X, send to peer P" from the packets they relay (P = the neighbour the
     * first copy came from). Later packets for X go as unicast instead of being flooded.
     */
    public boolean routeLearning = true;
    public long routeTtlMs = 120_000;
    public int routeCacheMax = 64;
    /**
     * Destination sends a tiny ACK back. No ACK after ackTimeoutMs (doubling each time) means the
     * sender forgets the learned route and re-sends by flooding, up to maxRetries times.
     */
    public boolean ackEnabled = true;
    public long ackTimeoutMs = 3_000;
    public int maxRetries = 2;

    // ---- zone-to-zone mesh and the role of the server ----
    /**
     * True when every zone has direct offline links to every other zone (spread over several border
     * nodes). A missing direct link to the destination zone then means "ask another node of my zone".
     * False = ring with shortcut links, forwarded greedily by gateways.
     */
    public boolean fullMesh = false;
    /**
     * false (default): the mesh is the main path, the server only helps: when the mesh has no route
     * and after an ACK timeout. true: an online sender goes straight through the server.
     */
    public boolean serverFirst = false;
}
