package com.caizhitong.integration.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 生产 HTTP 适配：固定 https://api.deepseek.com/chat/completions，
 * Bearer 密钥仅从 DEEPSEEK_API_KEY 读取，模型取 DEEPSEEK_MODEL（默认 deepseek-flash）。
 * 不跟随重定向，不接收请求中的 baseUrl/代理；401/402/422 等配置或请求错误不盲目重试，
 * 429/5xx/超时交给上层任务的有界重试。密钥未配置明确失败，无 fake 成功。
 */
@Component
public class DeepSeekClient {
    public static final String DEFAULT_MODEL = "deepseek-flash";
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");

    public static final class AiCallException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        public AiCallException(String code, boolean retryable, String message) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }

    public record Completion(String content, String finishReason, String model, long durationMs) {}

    private final String apiKey;
    private final String model;
    private final URI endpoint;
    private final OkHttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public DeepSeekClient(@Value("${DEEPSEEK_API_KEY:}") String apiKey,
                          @Value("${DEEPSEEK_MODEL:" + DEFAULT_MODEL + "}") String model) {
        this(apiKey, model, ENDPOINT);
    }

    /** 测试注入：仅配置期替换端点；请求路径不接受任何 baseUrl。 */
    DeepSeekClient(String apiKey, String model, URI endpoint) {
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        this.endpoint = endpoint;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .callTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    /** 只认 finish_reason=stop 与非空 content；其余按失败处理。 */
    public Completion complete(String system, String user, int maxTokens) {
        if (apiKey == null || apiKey.isBlank())
            throw new AiCallException("AI_KEY_MISSING", false, "AI 密钥未配置");
        String body = requestBody(system, user, maxTokens);
        Request request = new Request.Builder().url(endpoint.toString())
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build();
        long start = System.nanoTime();
        byte[] raw;
        try (var response = http.newCall(request).execute()) {
            int status = response.code();
            if (status == 401 || status == 402 || status == 422) throw statusError(status, false);
            if (status == 429 || status >= 500) throw statusError(status, true);
            if (status != 200) throw statusError(status, false);
            try (InputStream stream = response.body().byteStream()) { raw = readCapped(stream); }
        } catch (java.io.InterruptedIOException e) {
            throw new AiCallException("AI_TIMEOUT", true, "AI 调用超时");
        } catch (IOException e) {
            throw new AiCallException("AI_UPSTREAM", true, "AI 调用失败");
        }
        if (raw == null) throw new AiCallException("AI_RESPONSE_TOO_LARGE", false, "AI 响应过大");
        var tree = parse(raw);
        var choice = tree.path("choices");
        if (!choice.isArray() || choice.isEmpty()) throw new AiCallException("AI_BAD_RESPONSE", false, "AI 响应结构异常");
        var message = choice.get(0).path("message");
        String content = message.path("content").isTextual() ? message.path("content").asString() : null;
        String finish = choice.get(0).path("finish_reason").isTextual() ? choice.get(0).path("finish_reason").asString() : null;
        String usedModel = tree.path("model").isTextual() ? tree.path("model").asString() : model;
        if (!"stop".equals(finish) || content == null || content.isBlank())
            throw new AiCallException("AI_BAD_RESPONSE", false, "AI 响应不完整");
        return new Completion(content, finish, usedModel, (System.nanoTime() - start) / 1_000_000);
    }

    /** 响应上限 1MiB；超限返回 null 由调用方统一失败。 */
    private byte[] readCapped(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) > 0) {
            total += read;
            if (total > 1024 * 1024) return null;
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private tools.jackson.databind.JsonNode parse(byte[] raw) {
        try {
            return new tools.jackson.databind.json.JsonMapper().readTree(raw);
        } catch (RuntimeException e) {
            throw new AiCallException("AI_BAD_RESPONSE", false, "AI 响应不是有效 JSON");
        }
    }

    private AiCallException statusError(int status, boolean retryable) {
        String code = switch (status) {
            case 401 -> "AI_UNAUTHORIZED";
            case 402 -> "AI_PAYMENT";
            case 422 -> "AI_REQUEST";
            case 429 -> "AI_RATE_LIMITED";
            default -> "AI_UPSTREAM";
        };
        return new AiCallException(code, retryable, "AI 调用被拒绝");
    }

    private String requestBody(String system, String user, int maxTokens) {
        return new StringBuilder()
                .append("{\"model\":\"").append(model).append("\"")
                .append(",\"stream\":false")
                .append(",\"thinking\":{\"type\":\"disabled\"}")
                .append(",\"max_tokens\":").append(maxTokens)
                .append(",\"response_format\":{\"type\":\"json_object\"}")
                .append(",\"messages\":[")
                .append("{\"role\":\"system\",\"content\":").append(json(system)).append("},")
                .append("{\"role\":\"user\",\"content\":").append(json(user)).append("}")
                .append("]}")
                .toString();
    }

    private String json(String text) {
        return new tools.jackson.databind.json.JsonMapper().writeValueAsString(text);
    }

    public String model() { return model; }
}
