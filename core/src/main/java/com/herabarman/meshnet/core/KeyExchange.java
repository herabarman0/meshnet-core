package com.herabarman.meshnet.core;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.KeyAgreement;

/** X25519 key agreement helper. Note: needs a provider with X25519 (JDK 11+, Android 13+ or Conscrypt/BouncyCastle). */
public final class KeyExchange {
    private KeyExchange() {}

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return 32 byte AES-256 key = SHA-256(shared secret) */
    public static byte[] deriveKey(PrivateKey mine, PublicKey theirs) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(mine);
            ka.doPhase(theirs, true);
            return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
