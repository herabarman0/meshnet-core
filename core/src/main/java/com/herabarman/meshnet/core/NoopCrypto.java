package com.herabarman.meshnet.core;

public final class NoopCrypto implements Crypto {
    @Override public byte[] encrypt(byte[] plain, int src, int dst, long msgId) { return plain; }
    @Override public byte[] decrypt(byte[] cipher, int src, int dst, long msgId) { return cipher; }
}
