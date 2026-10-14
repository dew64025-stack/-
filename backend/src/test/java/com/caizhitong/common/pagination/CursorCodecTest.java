package com.caizhitong.common.pagination;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SensitiveDataCrypto;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class CursorCodecTest {
    byte[] key() { byte[] key = new byte[32]; key[0] = 1; return key; }
    CursorCodec codec = new CursorCodec(new SensitiveDataCrypto(new byte[32], key()), JsonMapper.builder().build());
    @Test void signedCursorRoundTripsButCannotCrossCampusOrFilters() {
        var value = new CursorPayload("campus=1;filter=open", Map.of("id", "10001", "createdAt", "2026-10-13"));
        String token = codec.encode(value);
        assertEquals(value, codec.decode(token, "campus=1;filter=open"));
        assertThrows(BusinessException.class, () -> codec.decode(token, "campus=2;filter=open"));
        assertThrows(BusinessException.class, () -> codec.decode(token, "campus=1;filter=closed"));
        assertThrows(BusinessException.class, () -> codec.decode("A" + token.substring(1), value.scope()));
    }
    @Test void malformedAndTooLongCursorsAreRejectedAndPaginationIsBounded() {
        for (String token : new String[]{"%%%", "a.b.c", "x".repeat(4097)})
            assertEquals(400, assertThrows(BusinessException.class, () -> codec.decode(token, "scope")).getStatus());
        assertEquals(20, PageLimits.resolve(null)); assertEquals(100, PageLimits.resolve(100));
        assertThrows(BusinessException.class, () -> PageLimits.resolve(101));
        assertThrows(BusinessException.class, () -> PageLimits.resolve(0));
    }
    @Test void signatureBase64MustBeCanonicalSoChangingUnusedBitsIsRejected() {
        String token = codec.encode(new CursorPayload("scope", Map.of("id", "1")));
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int last = alphabet.indexOf(token.charAt(token.length()-1));
        String changed = token.substring(0, token.length()-1) + alphabet.charAt(last + 1);
        assertThrows(BusinessException.class, () -> codec.decode(changed, "scope"));
    }
}
