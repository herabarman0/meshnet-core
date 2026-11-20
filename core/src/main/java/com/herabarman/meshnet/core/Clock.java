package com.herabarman.meshnet.core;

/** Time source + timer. Real clock on Android, virtual clock in the simulator. */
public interface Clock {
    long nowMs();
    void schedule(long delayMs, Runnable task);
}
