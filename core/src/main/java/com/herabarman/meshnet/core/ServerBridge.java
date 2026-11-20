package com.herabarman.meshnet.core;

/** Internet fast path through a central server. */
public interface ServerBridge {
    /** True if this node currently has internet access to the server. */
    boolean available();

    /** True if the server knows the destination is online right now. */
    boolean isReachable(int nodeId);

    /** Hand the encoded packet to the server for delivery to dstNode. */
    void send(int dstNode, byte[] data);
}
