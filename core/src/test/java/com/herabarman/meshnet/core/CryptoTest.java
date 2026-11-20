package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import org.junit.jupiter.api.Test;

class CryptoTest {
    private static final byte[] KEY = new byte[32];

    @Test
    void roundTripAndSmallOverhead() {
        AesGcmCrypto c = new AesGcmCrypto(KEY);
        byte[] plain = "hello mesh".getBytes();
        byte[] enc = c.encrypt(plain, 1, 2, 77L);
        assertEquals(plain.length + AesGcmCrypto.TAG_BYTES, enc.length);
        assertArrayEquals(plain, c.decrypt(enc, 1, 2, 77L));
    }

    @Test
    void tamperedOrRedirectedPacketsFailAuth() {
        AesGcmCrypto c = new AesGcmCrypto(KEY);
        byte[] enc = c.encrypt("secret".getBytes(), 1, 2, 77L);
        byte[] bad = enc.clone();
        bad[0] ^= 1;
        assertNull(c.decrypt(bad, 1, 2, 77L));
        assertNull(c.decrypt(enc, 1, 3, 77L)); // wrong dst
        assertNull(c.decrypt(enc, 9, 2, 77L)); // wrong src
    }

    @Test
    void x25519BothSidesDeriveSameKey() {
        KeyPair a = KeyExchange.generateKeyPair();
        KeyPair b = KeyExchange.generateKeyPair();
        byte[] k1 = KeyExchange.deriveKey(a.getPrivate(), b.getPublic());
        byte[] k2 = KeyExchange.deriveKey(b.getPrivate(), a.getPublic());
        assertArrayEquals(k1, k2);
        assertEquals(32, k1.length);
        AesGcmCrypto ca = new AesGcmCrypto(k1);
        AesGcmCrypto cb = new AesGcmCrypto(k2);
        assertArrayEquals("hi".getBytes(), cb.decrypt(ca.encrypt("hi".getBytes(), 1, 2, 5L), 1, 2, 5L));
    }
}
