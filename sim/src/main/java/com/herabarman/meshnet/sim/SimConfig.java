package com.herabarman.meshnet.sim;

/** All simulation parameters. */
public final class SimConfig implements Cloneable {
    public int nodes = 10_000;
    public int zoneSize = 15;
    public int gatewayCount = 2;
    public int messages = 1_000;
    /** Cap on backbone links per zone (0 = log-scale default). */
    public int maxFanOut = 0;

    public double onlineRatio = 0.2;
    /** Fraction of nodes that go down every churn interval. */
    public double churn = 0.01;
    /** Fraction of gateways killed at the midpoint of the run. */
    public double gatewayFail = 0.05;
    /** Fraction of messages sent inside the sender's own zone. */
    public double localRatio = 0.3;
    public double lossRate = 0.0;

    public long seed = 1;
    public long durationMs = 60_000;
    public long warmupMs = 2_000;
    public long drainMs = 60_000;
    public long churnIntervalMs = 5_000;
    public long electionDelayMs = 2_000;

    public int intraMs = 25;
    public int backboneMs = 60;
    public int serverMs = 80;
    public int jitterMs = 10;

    public boolean encrypt = false;

    // flood optimisation, see MeshConfig
    public int floodJitterMs = 40;
    public int counterThreshold = 3;
    public int floodFanout = 0;

    // route learning + ACK, see MeshConfig
    public boolean routeLearning = true;
    public boolean ackEnabled = true;
    public long ackTimeoutMs = 3_000;
    public int maxRetries = 2;

    /** Messages per conversation (a pair that talks back and forth). 1 = every message is a new pair. */
    public int conversationLength = 6;

    public SimConfig copy() {
        try {
            return (SimConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }
}
