package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM with a zero-overhead nonce: nonce = msgId(8) + src(4), never sent on the wire.
 * Only cost is the 16 byte auth tag. src and dst are bound as associated data,
 * so a relay cannot redirect or re-attribute a packet without breaking the tag.
 */
public final class AesGcmCrypto implements Crypto {
    public static final int TAG_BYTES = 16;
    private final SecretKeySpec key;

    public AesGcmCrypto(byte[] keyBytes) {
        if (keyBytes.length != 16 && keyBytes.length != 32) throw new IllegalArgumentException("key must be 16 or 32 bytes");
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] nonce(int src, long msgId) {
        return ByteBuffer.allocate(12).putLong(msgId).putInt(src).array();
    }

    private static byte[] aad(int src, int dst) {
        return ByteBuffer.allocate(8).putInt(src).putInt(dst).array();
    }

    @Override
    public byte[] encrypt(byte[] plain, int src, int dst, long msgId) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce(src, msgId)));
            c.updateAAD(aad(src, dst));
            return c.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] decrypt(byte[] cipher, int src, int dst, long msgId) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce(src, msgId)));
            c.updateAAD(aad(src, dst));
            return c.doFinal(cipher);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
