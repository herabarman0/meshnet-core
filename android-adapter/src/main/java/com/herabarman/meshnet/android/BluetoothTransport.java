package com.herabarman.meshnet.android;

import com.herabarman.meshnet.core.Transport;

/**
 * STUB. Real implementation goes into your Android app module (it needs android.bluetooth.*).
 *
 * What it must do:
 *  - map each Bluetooth MAC address to a stable int peerId (e.g. persistent device id)
 *  - keep one RFCOMM/BLE connection per peer, max 3-7 at a time (Bluetooth Classic limit)
 *  - send(): write Packet bytes to that peer's stream (the 22 byte header carries the length,
 *    so the reader can frame: read 22 bytes, read payloadLen more bytes)
 *  - on bytes received: listener.onReceive(peerId, packetBytes)
 *  - on connect/disconnect: listener.onPeerUp(peerId) / listener.onPeerDown(peerId)
 * A Wi-Fi Direct or WebSocket transport implements the same three calls.
 */
public abstract class BluetoothTransport implements Transport {
    protected Listener listener;

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }
}
