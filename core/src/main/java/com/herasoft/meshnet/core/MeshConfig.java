package com.herasoft.meshnet.core;

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
}
