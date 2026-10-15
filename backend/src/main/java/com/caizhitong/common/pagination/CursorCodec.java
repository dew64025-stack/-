package com.caizhitong.common.pagination;
import com.caizhitong.common.SensitiveDataCrypto;
import tools.jackson.databind.json.JsonMapper;
import com.caizhitong.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.stereotype.Component;
@Component
public class CursorCodec {
    private final SensitiveDataCrypto crypto;
    private final JsonMapper mapper;
    public CursorCodec(SensitiveDataCrypto crypto, JsonMapper mapper) { this.crypto = crypto; this.mapper = mapper; }
    public String encode(CursorPayload payload) {
        if (payload.scope() == null || payload.scope().isBlank() || payload.sortKeys().isEmpty()) throw invalid();
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        String token = body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(crypto.searchHash("cursor:v1", body));
        if (token.length() > 4096) throw invalid();
        return token;
    }
    public CursorPayload decode(String token, String scope) {
        if (token == null || token.length() > 4096 || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) throw invalid();
        try {
            String[] parts = token.split("\\.");
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(signature).equals(parts[1])) throw invalid();
            if (!MessageDigest.isEqual(signature, crypto.searchHash("cursor:v1", parts[0]))) throw invalid();
            byte[] body = Base64.getUrlDecoder().decode(parts[0]);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(body).equals(parts[0])) throw invalid();
            CursorPayload payload = mapper.readValue(body, CursorPayload.class);
            if (!payload.scope().equals(scope) || payload.sortKeys().isEmpty()) throw invalid();
            return payload;
        } catch (RuntimeException e) { throw invalid(); }
    }
    private BusinessException invalid() { return new BusinessException(400, "INVALID_CURSOR", "分页游标无效"); }
}
