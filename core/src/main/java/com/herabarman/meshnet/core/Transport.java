package com.herabarman.meshnet.core;

/** Link layer abstraction. Bluetooth, Wi-Fi Direct, WebSocket and the simulator all implement this. */
public interface Transport {
    /** Send raw bytes to a directly connected peer. Fire and forget. */
    void send(int peerId, byte[] data);

    /** The engine registers itself here. */
    void setListener(Listener listener);

    interface Listener {
        void onReceive(int fromPeer, byte[] data);
        void onPeerUp(int peerId);
        void onPeerDown(int peerId);
    }
}
