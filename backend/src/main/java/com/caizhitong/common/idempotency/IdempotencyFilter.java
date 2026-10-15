package com.caizhitong.common.idempotency;

import com.caizhitong.common.*;
import com.caizhitong.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Binds a command digest only; database persistence belongs to the application transaction. */
public final class IdempotencyFilter extends OncePerRequestFilter {
    private static final int MAX_COMMAND_BYTES = 1_048_576;
    private final JsonMapper mapper; private final SensitiveDataCrypto crypto; private final FilterErrors errors;
    public IdempotencyFilter(JsonMapper mapper, SensitiveDataCrypto crypto, FilterErrors errors) { this.mapper = mapper; this.crypto = crypto; this.errors = errors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !Set.of("POST", "PUT", "DELETE", "PATCH").contains(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/") || RoutePolicy.credentialException(request.getMethod(), request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        CachedCommand wrapped;
        try {
            String key = request.getHeader("Idempotency-Key");
            if (Collections.list(request.getHeaders("Idempotency-Key")).size() != 1 || key == null || !key.matches("[\\x20-\\x7E]{8,128}")) throw invalid();
            byte[] body = request.getInputStream().readNBytes(MAX_COMMAND_BYTES + 1);
            if (body.length > MAX_COMMAND_BYTES) throw new BusinessException(413, "COMMAND_TOO_LARGE", "业务请求过大");
            String normalized = "";
            if (body.length > 0) {
                if (request.getContentType() == null || !request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json"))
                    throw new BusinessException(400, "INVALID_COMMAND", "业务写入需要JSON请求");
                try { normalized = mapper.writeValueAsString(canonical(mapper.readTree(body))); }
                catch (RuntimeException e) { throw new BusinessException(400, "INVALID_COMMAND", "请求JSON无效"); }
            }
            Map<String, List<String>> query = new TreeMap<>();
            request.getParameterMap().forEach((name, values) -> query.put(name, Arrays.asList(values)));
            String material = mapper.writeValueAsString(List.of(normalized, query));
            String digest = HexFormat.of().formatHex(crypto.searchHash("idempotency-request:v1", material));
            var context = new IdempotencyContext(request.getMethod(), request.getRequestURI(), key, digest);
            request.setAttribute(IdempotencyContext.REQUEST_ATTRIBUTE, context);
            wrapped = new CachedCommand(request, body);
        } catch (BusinessException e) { errors.write(request, response, e); return; }
        chain.doFilter(wrapped, response);
    }
    private Object canonical(JsonNode node) {
        if (node.isObject()) { Map<String,Object> result = new TreeMap<>(); node.properties().forEach(entry -> result.put(entry.getKey(), canonical(entry.getValue()))); return result; }
        if (node.isArray()) { List<Object> result = new ArrayList<>(); node.forEach(value -> result.add(canonical(value))); return result; }
        return node;
    }
    private BusinessException invalid() { return new BusinessException(400, "INVALID_IDEMPOTENCY_KEY", "业务写入需要8至128位ASCII的Idempotency-Key"); }
    private static final class CachedCommand extends HttpServletRequestWrapper {
        private final byte[] body;
        CachedCommand(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            var stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                public boolean isFinished() { return stream.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON command body"); }
                public int read() { return stream.read(); }
                public int read(byte[] bytes, int offset, int length) { return stream.read(bytes, offset, length); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    }
}
