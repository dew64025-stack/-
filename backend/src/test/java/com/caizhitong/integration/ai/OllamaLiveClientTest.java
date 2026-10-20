package com.caizhitong.integration.ai;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in local model check. Run with OLLAMA_LIVE_TEST=1 after `ollama pull qwen3:8b`.
 * It is deliberately skipped in the normal suite because it needs a live Ollama server and
 * GPU/CPU inference budget; it is not a substitute for a production AI acceptance test.
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_LIVE_TEST", matches = "1")
class OllamaLiveClientTest {
    @Test
    void localQwen3ReturnsJsonCompletion() {
        var client = new DeepSeekClient("ollama", "qwen3:8b",
                URI.create("http://127.0.0.1:11434/api/chat"), true, 300);
        var completion = client.complete(
                "只输出一个 JSON 对象，不要 Markdown，不要解释。",
                "请输出 {\"ok\":true,\"model\":\"qwen3:8b\"}",
                128);
        assertTrue(completion.content().contains("\"ok\""));
        assertTrue(completion.content().contains("true"));
        assertEquals("stop", completion.finishReason());
    }
}
