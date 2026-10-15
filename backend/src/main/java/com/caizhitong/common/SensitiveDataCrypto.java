package com.caizhitong.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SensitiveDataCrypto {
    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    public SensitiveDataCrypto(byte[] encryptionKey, byte[] hmacKey) {
        if (encryptionKey == null || encryptionKey.length != 32 || hmacKey == null || hmacKey.length < 32
                || MessageDigest.isEqual(encryptionKey, hmacKey)) {
            throw new IllegalArgumentException("Independent AES-256 and HMAC keys are required");
        }
        this.encryptionKey = new SecretKeySpec(encryptionKey.clone(), "AES");
        this.hmacKey = new SecretKeySpec(hmacKey.clone(), "HmacSHA256");
    }

    public byte[] encrypt(String purpose, String text) {
        byte[] aad = purposeBytes(purpose);
        if (text == null) throw new IllegalArgumentException("Text is required");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE_BYTES + encrypted.length)
                    .put(VERSION).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Encryption failed");
        }
    }

    public String decrypt(String purpose, byte[] encrypted) {
        byte[] aad = purposeBytes(purpose);
        if (encrypted == null || encrypted.length < 1 + NONCE_BYTES + TAG_BITS / 8 || encrypted[0] != VERSION) {
            throw new IllegalArgumentException("Invalid encrypted value");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(encrypted, 1, 1 + NONCE_BYTES)));
            cipher.updateAAD(aad);
            return new String(cipher.doFinal(encrypted, 1 + NONCE_BYTES, encrypted.length - 1 - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new IllegalArgumentException("Invalid encrypted value");
        }
    }

    public byte[] searchHash(String purpose, String text) {
        byte[] purposeBytes = purposeBytes(purpose);
        if (text == null) throw new IllegalArgumentException("Text is required");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(purposeBytes.length).array());
            mac.update(purposeBytes);
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Search hashing failed");
        }
    }

    private byte[] purposeBytes(String purpose) {
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("Purpose is required");
        return purpose.getBytes(StandardCharsets.UTF_8);
    }
}
