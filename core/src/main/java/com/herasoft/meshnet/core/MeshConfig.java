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
}
