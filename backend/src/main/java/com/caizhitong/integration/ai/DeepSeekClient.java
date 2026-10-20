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
 * OpenAI-compatible AI 适配。默认使用 DeepSeek 固定端点；AI_PROVIDER=ollama 时切换到本机
 * Ollama 的 /v1/chat/completions（模型默认 qwen3:8b），用于离线/内网联调。
 * 端点只由部署配置决定，不接收请求中的 baseUrl；不跟随重定向。
 * 401/402/422 等配置或请求错误不盲目重试，429/5xx/超时交给上层任务的有界重试。
 * 密钥未配置明确失败，无 fake 成功；Ollama 本机端点不要求密钥，使用占位值。
 */
@Component
public class DeepSeekClient {
    public static final String DEFAULT_MODEL = "deepseek-flash";
    public static final String DEFAULT_OLLAMA_MODEL = "qwen3:8b";
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private static final URI OLLAMA_ENDPOINT = URI.create("http://127.0.0.1:11434/api/chat");

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
    private final boolean ollama;
    private final OkHttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public DeepSeekClient(@Value("${app.ai.provider:deepseek}") String provider,
                          @Value("${app.ai.endpoint:}") String configuredEndpoint,
                          @Value("${app.ai.model:}") String configuredModel,
                          @Value("${app.ai.api-key:}") String configuredApiKey,
                          @Value("${app.ai.timeout-seconds:60}") int timeoutSeconds,
                          @Value("${DEEPSEEK_API_KEY:}") String deepseekApiKey,
                          @Value("${DEEPSEEK_MODEL:" + DEFAULT_MODEL + "}") String deepseekModel) {
        this(resolveApiKey(provider, configuredApiKey, deepseekApiKey),
                resolveModel(provider, configuredModel, deepseekModel),
                resolveEndpoint(provider, configuredEndpoint),
                isOllamaProvider(provider),
                timeoutSeconds);
    }

    private static boolean isOllamaProvider(String provider) {
        return "ollama".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    private static String resolveApiKey(String provider, String configured, String deepseekKey) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        return isOllamaProvider(provider) ? "ollama" : (deepseekKey == null ? "" : deepseekKey);
    }

    private static String resolveModel(String provider, String configured, String deepseekModel) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        if (isOllamaProvider(provider)) return DEFAULT_OLLAMA_MODEL;
        return (deepseekModel == null || deepseekModel.isBlank()) ? DEFAULT_MODEL : deepseekModel;
    }

    private static URI resolveEndpoint(String provider, String configured) {
        if (configured != null && !configured.isBlank()) return URI.create(configured.trim());
        return isOllamaProvider(provider) ? OLLAMA_ENDPOINT : ENDPOINT;
    }

    /** 兼容既有测试替身：固定 DeepSeek 端点。 */
    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, ENDPOINT);
    }

    /** 测试注入：仅配置期替换端点；请求路径不接受任何 baseUrl。 */
    DeepSeekClient(String apiKey, String model, URI endpoint) {
        this(apiKey, model, endpoint, false, 60);
    }

    DeepSeekClient(String apiKey, String model, URI endpoint, boolean ollama, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        this.endpoint = endpoint;
        this.ollama = ollama;
        int timeout = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .callTimeout(Duration.ofSeconds(timeout))
                .readTimeout(Duration.ofSeconds(timeout))
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
        if (ollama) {
            // Ollama native /api/chat shape. Qwen3's OpenAI-compatible endpoint emits thinking
            // tokens into message.reasoning and may finish with empty content, so the local
            // provider uses the native endpoint with think=false and format=json.
            if (tree.path("error").isTextual()) throw new AiCallException("AI_UPSTREAM", false, "AI 调用被拒绝");
            String content = tree.path("message").path("content").isTextual()
                    ? tree.path("message").path("content").asString() : null;
            boolean done = tree.path("done").asBoolean(false);
            String finish = tree.path("done_reason").isTextual() ? tree.path("done_reason").asString()
                    : (done ? "stop" : null);
            String usedModel = tree.path("model").isTextual() ? tree.path("model").asString() : model;
            if (!done || !"stop".equals(finish) || content == null || content.isBlank())
                throw new AiCallException("AI_BAD_RESPONSE", false, "AI 响应不完整");
            return new Completion(content, finish, usedModel, (System.nanoTime() - start) / 1_000_000);
        }
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
        if (ollama) {
            return new StringBuilder()
                    .append("{\"model\":\"").append(model).append("\"")
                    .append(",\"stream\":false")
                    .append(",\"think\":false")
                    .append(",\"format\":\"json\"")
                    .append(",\"options\":{\"num_predict\":").append(maxTokens).append("}")
                    .append(",\"messages\":[")
                    .append("{\"role\":\"system\",\"content\":").append(json(system)).append("},")
                    .append("{\"role\":\"user\",\"content\":").append(json(user)).append("}")
                    .append("]}")
                    .toString();
        }
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
