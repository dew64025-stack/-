package com.caizhitong.integration.notice;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF 防护单元测试:受控地址矩阵与重定向逐跳复验。
 * 本机/私网/环回/链路本地/IPv4映射IPv6/保留地址全部拒绝,不依赖外网探测。
 * TLS 成功路径需要可解析到本机的非保留公网地址,在本机网络不可密闭复现,另行联调验收。
 */
class SafeUrlReaderTest {
    private final SafeUrlReader reader = new SafeUrlReader();

    private SafeUrlReader.FetchFailure blocked(String url) {
        return assertThrows(SafeUrlReader.FetchFailure.class, () -> reader.validate(url));
    }

    @Test
    void rejectsInsecureSchemesUserinfoPortsAndFragments() {
        assertEquals("URL_SCHEME", blocked("http://example.edu/notice").code());
        assertEquals("URL_SCHEME", blocked("ftp://example.edu/notice").code());
        assertEquals("URL_INVALID", blocked("https://user@example.edu/notice").code());
        assertEquals("URL_PORT", blocked("https://example.edu:8443/notice").code());
        assertEquals("URL_INVALID", blocked("https://example.edu/notice#frag").code());
        assertEquals("URL_INVALID", blocked("https:///notice").code());
    }

    @Test
    void rejectsAllPrivateLoopbackLinkLocalAndReservedRanges() {
        for (String ip : List.of("127.0.0.1", "127.8.8.8", "10.0.0.1", "10.255.255.255", "192.168.1.1",
                "172.16.0.1", "172.31.255.255", "169.254.169.254", "100.64.0.1", "0.0.0.0",
                "192.0.2.1", "198.18.0.1", "198.19.255.255", "198.51.100.1", "203.0.113.1",
                "224.0.0.1", "255.255.255.255")) {
            assertEquals("URL_BLOCKED", blocked("https://" + ip + "/x").code(), ip);
        }
        for (String ip : List.of("[::1]", "[::]", "[fe80::1]", "[fd00::1]", "[fc00::1]",
                "[::ffff:127.0.0.1]", "[::ffff:192.168.1.1]", "[2001:db8::1]")) {
            assertEquals("URL_BLOCKED", blocked("https://" + ip + "/x").code(), ip);
        }
    }

    @Test
    void hostnameResolvingToLoopbackIsBlockedWithoutConnecting() {
        assertEquals("URL_BLOCKED", blocked("https://localhost/notice").code());
    }

    @Test
    void redirectTargetsAreRevalidatedPerHop() {
        // 降级到 HTTP 拒绝
        var downgrade = assertThrows(SafeUrlReader.FetchFailure.class,
                () -> reader.validateRedirect("https://example.edu/a", "http://example.edu/b"));
        assertEquals("URL_SCHEME", downgrade.code());
        // 跳转到环回/私网拒绝
        assertEquals("URL_BLOCKED", assertThrows(SafeUrlReader.FetchFailure.class,
                () -> reader.validateRedirect("https://example.edu/a", "https://127.0.0.1/b")).code());
        assertEquals("URL_BLOCKED", assertThrows(SafeUrlReader.FetchFailure.class,
                () -> reader.validateRedirect("https://example.edu/a", "https://10.0.0.8/b")).code());
        // 跳转带端口/userinfo 拒绝
        assertEquals("URL_PORT", assertThrows(SafeUrlReader.FetchFailure.class,
                () -> reader.validateRedirect("https://example.edu/a", "https://example.edu:8443/b")).code());
        assertEquals("URL_INVALID", assertThrows(SafeUrlReader.FetchFailure.class,
                () -> reader.validateRedirect("https://example.edu/a", "https://u@example.edu/b")).code());
        // 合法 HTTPS 跳转通过复验
        assertDoesNotThrow(() -> reader.validateRedirect("https://example.edu/a", "https://example.edu/b"));
        // 跳数上限
        assertEquals(3, SafeUrlReader.MAX_REDIRECTS);
    }

    @Test
    void fetchToLoopbackFailsBeforeAnyConnection() {
        SafeUrlReader.FetchFailure failure = assertThrows(SafeUrlReader.FetchFailure.class,
                () -> reader.fetch("https://localhost/notice"));
        assertEquals("URL_BLOCKED", failure.code());
        assertFalse(failure.retryable());
        assertFalse(failure.getMessage().contains("localhost")); // 不回显完整上游 URL
    }

    @Test
    void failureCodesAreStableAndNonRetryableForConfigErrors() {
        assertEquals("TIMEOUT", new SafeUrlReader.FetchFailure("TIMEOUT", true, "x").code());
        assertTrue(new SafeUrlReader.FetchFailure("TIMEOUT", true, "x").retryable());
        assertFalse(new SafeUrlReader.FetchFailure("URL_BLOCKED", false, "x").retryable());
    }

    private void assertDoesNotThrow(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            throw new AssertionError("Expected no exception but got " + e.getClass().getSimpleName(), e);
        }
    }
}
