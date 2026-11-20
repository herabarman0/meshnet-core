package com.herasoft.meshnet.core;

/** Pluggable payload encryption. Header stays in clear so relays can route. */
public interface Crypto {
    byte[] encrypt(byte[] plain, int src, int dst, long msgId);

    /** @return plaintext, or null if authentication fails */
    byte[] decrypt(byte[] cipher, int src, int dst, long msgId);
}
