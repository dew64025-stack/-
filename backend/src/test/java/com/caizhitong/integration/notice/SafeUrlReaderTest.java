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
 * TLS 成功/拒绝由 SafeUrlReaderTlsTest 的受控真实 TLS peer 验证，公网仍须另行联调。
 */
class SafeUrlReaderTest {
    private final SafeUrlReader reader = new SafeUrlReader();

    @Test
    void identityGzipChunkedChunkedGzipAndDeflatePreserveEntireOriginal() throws Exception {
        String original = "notice fixture ".repeat(200);
        byte[] plain = original.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(original, body(plain, List.of("Content-Length: " + plain.length), null));
        byte[] gzip = compress(plain, true);
        assertEquals(original, body(gzip, List.of("Content-Length: " + gzip.length), "gzip"));
        assertEquals(original, body(chunked(plain), List.of("Transfer-Encoding: chunked"), null));
        assertEquals(original, body(chunked(gzip), List.of("Transfer-Encoding: chunked"), "gzip"));
        byte[] deflate = compress(plain, false);
        assertEquals(original, body(deflate, List.of("Content-Length: " + deflate.length), "deflate"));
    }

    @Test
    void truncatedAndMalformedFramingCannotBecomeSuccessfulImports() throws Exception {
        for (byte[] wire : List.of("8\r\nshort".getBytes(), "zz\r\ndata\r\n0\r\n\r\n".getBytes(),
                "4\r\ndataXX0\r\n\r\n".getBytes(), "4\r\ndata\r\n".getBytes()))
            assertThrows(Exception.class, () -> body(wire, List.of("Transfer-Encoding: chunked"), null));
        assertThrows(Exception.class, () -> body("short".getBytes(), List.of("Content-Length: 100"), null));
        assertThrows(Exception.class, () -> body("x".getBytes(), List.of("Content-Length: -1"), null));
    }

    @Test
    void compressedAndExpandedBodiesBothHaveBoundsAndDeadline() throws Exception {
        assertThrows(Exception.class, () -> body(new byte[SafeUrlReader.MAX_BODY_BYTES + 1], List.of(), null));
        byte[] bomb = compress(new byte[SafeUrlReader.MAX_BODY_BYTES + 1], true);
        assertThrows(Exception.class, () -> body(bomb, List.of("Content-Length: " + bomb.length), "gzip"));
        assertThrows(Exception.class, () -> body(new byte[0], List.of(), null, System.nanoTime() - 1));
    }

    private byte[] compress(byte[] bytes, boolean gzip) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        try (var stream = gzip ? new java.util.zip.GZIPOutputStream(output) : new java.util.zip.DeflaterOutputStream(output)) {
            stream.write(bytes);
        }
        return output.toByteArray();
    }

    private byte[] chunked(byte[] bytes) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        output.write((Integer.toHexString(bytes.length) + "\r\n").getBytes());
        output.write(bytes);
        output.write("\r\n0\r\nX-Fixture: done\r\n\r\n".getBytes());
        return output.toByteArray();
    }

    private String body(byte[] wire, List<String> headers, String encoding) throws Exception {
        return body(wire, headers, encoding, System.nanoTime() + 15_000_000_000L);
    }

    private String body(byte[] wire, List<String> headers, String encoding, long deadline) throws Exception {
        var socket = org.mockito.Mockito.mock(javax.net.ssl.SSLSocket.class);
        org.mockito.Mockito.when(socket.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(wire));
        Class<?> blockClass = Class.forName(SafeUrlReader.class.getName() + "$HeaderBlock");
        var constructor = blockClass.getDeclaredConstructor(String.class, List.class);
        constructor.setAccessible(true);
        Object block = constructor.newInstance("HTTP/1.1 200 OK", headers);
        var read = SafeUrlReader.class.getDeclaredMethod("readBody", javax.net.ssl.SSLSocket.class, blockClass, String.class, long.class);
        read.setAccessible(true);
        try {
            return new String((byte[]) read.invoke(reader, socket, block, encoding, deadline), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    @Test
    void tlsHandshakeAlreadyHasFiniteReadTimeout() throws Exception {
        var socket = org.mockito.Mockito.mock(javax.net.ssl.SSLSocket.class);
        var timeout = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> { timeout.set(call.getArgument(0)); return null; }).when(socket).setSoTimeout(org.mockito.Mockito.anyInt());
        org.mockito.Mockito.doAnswer(call -> {
            if (timeout.get() > 0 && timeout.get() <= 15000) throw new java.net.SocketTimeoutException("fixture handshake timed out");
            throw new AssertionError("TLS handshake began without bounded read timeout");
        }).when(socket).startHandshake();
        var factory = org.mockito.Mockito.mock(javax.net.ssl.SSLSocketFactory.class);
        org.mockito.Mockito.when(factory.createSocket(org.mockito.Mockito.any(java.net.Socket.class),
                org.mockito.Mockito.eq("example.edu"), org.mockito.Mockito.eq(443), org.mockito.Mockito.eq(true))).thenReturn(socket);
        var ssl = org.mockito.Mockito.mock(javax.net.ssl.SSLContext.class);
        org.mockito.Mockito.when(ssl.getSocketFactory()).thenReturn(factory);
        org.mockito.Mockito.when(ssl.getDefaultSSLParameters()).thenReturn(new javax.net.ssl.SSLParameters());
        var bounded = new SafeUrlReader(ssl, null);
        Class<?> targetClass = Class.forName(SafeUrlReader.class.getName() + "$Target");
        var constructor = targetClass.getDeclaredConstructor(String.class, String.class, java.net.InetAddress.class, String.class);
        constructor.setAccessible(true);
        Object target = constructor.newInstance("https://example.edu/", "example.edu", java.net.InetAddress.getByAddress(new byte[]{8,8,8,8}), "/");
        var connect = SafeUrlReader.class.getDeclaredMethod("connect", targetClass, long.class);
        connect.setAccessible(true);
        try (var transport = org.mockito.Mockito.mockConstruction(java.net.Socket.class)) {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> connect.invoke(bounded, target, System.nanoTime() + 15_000_000_000L));
            assertTrue(failure.getCause() instanceof java.net.SocketTimeoutException);
            org.mockito.Mockito.verify(transport.constructed().getFirst()).connect(
                    org.mockito.Mockito.eq(new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(new byte[]{8,8,8,8}),443)),
                    org.mockito.Mockito.intThat(value -> value > 0 && value <= 2000));
        }
    }

    @Test
    void trickledHandshakeIsClosedAtAbsoluteDeadlineAcrossMultipleReads() throws Exception {
        var socket = org.mockito.Mockito.mock(javax.net.ssl.SSLSocket.class);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> { closed.set(true); return null; }).when(socket).close();
        org.mockito.Mockito.doAnswer(call -> {
            // Each fragment arrives well before SO_TIMEOUT, but the whole handshake exceeds the budget.
            for (int fragment = 0; fragment < 40; fragment++) {
                if (closed.get()) throw new java.net.SocketException("fixture socket closed");
                Thread.sleep(25);
            }
            return null;
        }).when(socket).startHandshake();
        var factory = org.mockito.Mockito.mock(javax.net.ssl.SSLSocketFactory.class);
        org.mockito.Mockito.when(factory.createSocket(org.mockito.Mockito.any(java.net.Socket.class),
                org.mockito.Mockito.eq("example.edu"), org.mockito.Mockito.eq(443), org.mockito.Mockito.eq(true))).thenReturn(socket);
        var ssl = org.mockito.Mockito.mock(javax.net.ssl.SSLContext.class);
        org.mockito.Mockito.when(ssl.getSocketFactory()).thenReturn(factory);
        org.mockito.Mockito.when(ssl.getDefaultSSLParameters()).thenReturn(new javax.net.ssl.SSLParameters());
        var bounded = new SafeUrlReader(ssl, null);
        Class<?> targetClass = Class.forName(SafeUrlReader.class.getName() + "$Target");
        var constructor = targetClass.getDeclaredConstructor(String.class, String.class, java.net.InetAddress.class, String.class);
        constructor.setAccessible(true);
        Object target = constructor.newInstance("https://example.edu/", "example.edu", java.net.InetAddress.getByAddress(new byte[]{8,8,8,8}), "/");
        var connect = SafeUrlReader.class.getDeclaredMethod("connect", targetClass, long.class);
        connect.setAccessible(true);
        long start = System.nanoTime();
        try (var transport = org.mockito.Mockito.mockConstruction(java.net.Socket.class,
                (raw, context) -> org.mockito.Mockito.doAnswer(call -> { closed.set(true); return null; }).when(raw).close())) {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> connect.invoke(bounded, target, start + 150_000_000L));
            long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(elapsedMs < 600, "handshake exceeded absolute deadline: " + elapsedMs + "ms");
            assertTrue(failure.getCause() instanceof java.net.SocketTimeoutException);
            assertTrue(closed.get());
        }
    }

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
    void nonPublicIpv6IsRejectedBeforeConnectionAndPublicIpv6RemainsUsable() {
        for (String address : List.of("fec0::1", "febf:ffff::1", "feff:ffff::1", "ff00::1", "ff02::1",
                "::2", "64:ff9b::808:808", "2001::808:808", "2002:0808:0808::1", "3fff::1")) {
            assertEquals("URL_BLOCKED", blocked("https://[" + address + "]/notice").code(), address);
        }
        for (String address : List.of("2606:4700:4700::1111", "2001:4860:4860::8888"))
            assertDoesNotThrow(() -> reader.validate("https://[" + address + "]/notice"));
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
