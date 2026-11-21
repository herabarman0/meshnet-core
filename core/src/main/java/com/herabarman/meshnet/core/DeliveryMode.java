package com.herabarman.meshnet.core;

/** How the mesh and the internet server share the work. */
public enum DeliveryMode {
    /** The server is never used. Pure offline mesh. */
    OFFLINE_ONLY,
    /** The mesh is the main path. The server helps when the mesh has no route right now and for retries after an ACK timeout. */
    OFFLINE_FIRST,
    /** An online sender or border node goes straight through the server; the mesh carries the rest. */
    ONLINE_FIRST
}
