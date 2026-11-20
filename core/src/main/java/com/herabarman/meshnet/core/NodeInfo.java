package com.herabarman.meshnet.core;

/** What gateway election needs to know about a node. */
public final class NodeInfo {
    public final int id;
    public int zone;
    /** 0..1 */
    public double battery;
    /** 0..1 link quality */
    public double signal;
    /** Has internet access. */
    public boolean online;
    public boolean alive = true;

    public NodeInfo(int id, int zone, double battery, double signal, boolean online) {
        this.id = id;
        this.zone = zone;
        this.battery = battery;
        this.signal = signal;
        this.online = online;
    }
}
