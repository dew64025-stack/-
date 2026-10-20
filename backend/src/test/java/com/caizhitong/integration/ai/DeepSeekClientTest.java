package com.caizhitong.integration.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek 生产 HTTP 适配协议测试:本地受控 HTTP 服务替身,
 * 覆盖鉴权 Header、请求结构、finish_reason 判定、错误状态、响应上限与密钥缺失。
 * 真实付费联调需安全配置与明确验收,本测试不是真实模型验证。
 */
class DeepSeekClientTest {
    private static HttpServer server;
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/chat/completions", DeepSeekClientTest::handle);
        server.createContext("/api/chat", DeepSeekClientTest::handleNative);
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    private static void handle(HttpExchange exchange) throws IOException {
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String path = exchange.getRequestURI().getPath();
        String route = "/chat/completions".equals(path) ? "ok" : path.substring("/chat/completions/".length());
        byte[] body;
        int status = 200;
        switch (route) {
            case "ok" -> body = "{\"model\":\"deepseek-flash\",\"choices\":[{\"message\":{\"content\":\"{\\\"a\\\":1}\"},\"finish_reason\":\"stop\"}]}".getBytes();
            case "bad-json" -> body = "not json".getBytes();
            case "empty-content" -> body = "{\"model\":\"m\",\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}".getBytes();
            case "not-stopped" -> body = "{\"model\":\"m\",\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"length\"}]}".getBytes();
            case "unauthorized" -> { status = 401; body = "{}".getBytes(); }
            case "payment" -> { status = 402; body = "{}".getBytes(); }
            case "rate-limited" -> { status = 429; body = "{}".getBytes(); }
            case "server-error" -> { status = 500; body = "{}".getBytes(); }
            case "slow-body" -> {
                body = "{\"model\":\"m\",\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body, 0, 1);
                exchange.getResponseBody().flush();
                try { Thread.sleep(65_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { exchange.getResponseBody().write(body, 1, body.length - 1); } finally { exchange.close(); }
                return;
            }
            case "big" -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[8192];
                for (int i = 0; i < 200; i++) exchange.getResponseBody().write(chunk);
                exchange.close();
                return;
            }
            default -> { status = 404; body = "{}".getBytes(); }
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void handleNative(HttpExchange exchange) throws IOException {
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"a\\\":1}\"},"
                .concat("\"done\":true,\"done_reason\":\"stop\"}").getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private DeepSeekClient client() {
        return new DeepSeekClient("test-secret-key", "deepseek-flash",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"));
    }

    @Test
    void sendsFixedProtocolStructureWithBearerAuth() {
        var completion = client().complete("system-prompt", "user-content", 8192);
        assertEquals("{\"a\":1}", completion.content());
        assertEquals("stop", completion.finishReason());
        assertTrue(completion.durationMs() >= 0);
        assertEquals("Bearer test-secret-key", lastAuth.get());
        assertTrue(lastBody.get().contains("\"stream\":false"));
        assertTrue(lastBody.get().contains("\"thinking\":{\"type\":\"disabled\"}"));
        assertTrue(lastBody.get().contains("\"max_tokens\":8192"));
        assertTrue(lastBody.get().contains("\"response_format\":{\"type\":\"json_object\"}"));
        assertTrue(lastBody.get().contains("\"model\":\"deepseek-flash\""));
        assertTrue(lastBody.get().contains("system-prompt"));
        assertTrue(lastBody.get().contains("user-content"));
    }

    @Test
    void ollamaProtocolUsesNativeThinkFalseJsonMode() {
        var client = new DeepSeekClient("ollama", "qwen3:8b",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat"), true, 300);
        var completion = client.complete("system-prompt", "user-content", 256);
        assertEquals("{\"a\":1}", completion.content());
        assertEquals("Bearer ollama", lastAuth.get());
        assertTrue(lastBody.get().contains("\"model\":\"qwen3:8b\""));
        assertTrue(lastBody.get().contains("\"think\":false"));
        assertTrue(lastBody.get().contains("\"format\":\"json\""));
        assertTrue(lastBody.get().contains("\"num_predict\":256"));
        assertFalse(lastBody.get().contains("\"response_format\""));
        assertFalse(lastBody.get().contains("\"thinking\""));
    }

    @Test
    void missingKeyFailsExplicitlyWithoutCallingUpstream() {
        var client = new DeepSeekClient("", "deepseek-flash",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"));
        var failure = assertThrows(DeepSeekClient.AiCallException.class, () -> client.complete("s", "u", 10));
        assertEquals("AI_KEY_MISSING", failure.code());
        assertFalse(failure.retryable());
    }

    @Test
    void configErrorsDoNotRetryButServerErrorsDo() {
        assertEquals("AI_UNAUTHORIZED", code("unauthorized"));
        assertEquals("AI_PAYMENT", code("payment"));
        // 429/5xx 交给上层任务有界重试
        var rate = fail("rate-limited");
        assertEquals("AI_RATE_LIMITED", rate.code());
        assertTrue(rate.retryable());
        var fiveHundred = fail("server-error");
        assertEquals("AI_UPSTREAM", fiveHundred.code());
        assertTrue(fiveHundred.retryable());
        assertFalse(fail("unauthorized").retryable());
        assertFalse(fail("payment").retryable());
    }

    @Test
    void incompleteOrMalformedResponsesFailToManual() {
        assertFalse(fail("bad-json").retryable());
        assertFalse(fail("empty-content").retryable());
        assertEquals("AI_BAD_RESPONSE", fail("not-stopped").code());
        assertEquals("AI_RESPONSE_TOO_LARGE", fail("big").code());
        assertFalse(fail("big").retryable());
    }

    @Test
    void responseBodyMustFinishInsideOverallCallDeadline() {
        long start = System.nanoTime();
        var failure = fail("slow-body");
        assertEquals("AI_TIMEOUT", failure.code());
        assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 63);
    }

    private String code(String route) {
        return fail(route).code();
    }

    private DeepSeekClient.AiCallException fail(String route) {
        var client = new DeepSeekClient("k", "deepseek-flash",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions/" + route));
        return assertThrows(DeepSeekClient.AiCallException.class, () -> client.complete("s", "u", 10));
    }
}
