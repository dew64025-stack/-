package com.caizhitong.common;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {
    private static byte[] key(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    @Test void encryptsWithRandomNonceAndRoundTripsUnicode() {
        var crypto = new SensitiveDataCrypto(key(1), key(2));
        byte[] first = crypto.encrypt("phone", "校园13800000000");
        assertEquals("校园13800000000", crypto.decrypt("phone", first));
        assertFalse(Arrays.equals(first, crypto.encrypt("phone", "校园13800000000")));
        assertEquals(1, first[0]);
        assertEquals(1 + 12 + 16 + "校园13800000000".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, first.length);
    }

    @Test void tamperedCipherIsRejected() {
        var crypto = new SensitiveDataCrypto(key(1), key(2));
        byte[] cipher = crypto.encrypt("phone", "13800000000");
        cipher[cipher.length - 1] ^= 1;
        var error = assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("phone", cipher));
        assertFalse(error.getMessage().contains("13800000000"));
    }

    @Test void wrongPurposeAndWrongKeyAreRejected() {
        var crypto = new SensitiveDataCrypto(key(1), key(2));
        byte[] cipher = crypto.encrypt("phone", "13800000000");
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("studentNumber", cipher));
        assertThrows(IllegalArgumentException.class, () -> new SensitiveDataCrypto(key(3), key(2)).decrypt("phone", cipher));
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("phone", new byte[2]));
        cipher[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("phone", cipher));
    }

    @Test void searchHashIsDeterministicAndPurposeScoped() {
        var crypto = new SensitiveDataCrypto(key(1), key(2));
        assertArrayEquals(crypto.searchHash("phone", "123"), crypto.searchHash("phone", "123"));
        assertEquals(32, crypto.searchHash("phone", "123").length);
        assertFalse(Arrays.equals(crypto.searchHash("phone", "123"), crypto.searchHash("studentNumber", "123")));
        assertFalse(Arrays.equals(crypto.searchHash("a", "bc"), crypto.searchHash("ab", "c")));
        assertFalse(Arrays.equals(crypto.searchHash("phone", "123"), new SensitiveDataCrypto(key(1), key(3)).searchHash("phone", "123")));
    }

    @Test void rejectsInvalidOrSharedKeysAndCopiesKeys() {
        assertThrows(IllegalArgumentException.class, () -> new SensitiveDataCrypto(new byte[16], key(2)));
        assertThrows(IllegalArgumentException.class, () -> new SensitiveDataCrypto(key(1), new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new SensitiveDataCrypto(key(1), key(1)));
        byte[] encryptionKey = key(1);
        byte[] hashKey = key(2);
        var crypto = new SensitiveDataCrypto(encryptionKey, hashKey);
        byte[] encrypted = crypto.encrypt("phone", "123");
        byte[] hash = crypto.searchHash("phone", "123");
        Arrays.fill(encryptionKey, (byte) 9);
        Arrays.fill(hashKey, (byte) 9);
        assertEquals("123", crypto.decrypt("phone", encrypted));
        assertArrayEquals(hash, crypto.searchHash("phone", "123"));
        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt("", "123"));
    }
}
