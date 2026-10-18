package com.caizhitong.integration.notice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.springframework.stereotype.Component;

/**
 * 受限原文读取：仅 HTTPS、逐跳解析并验证全部 A/AAAA 目标、拒内网/环回/链路本地/保留地址、
 * TLS 校验原主机、连接固定到已验证地址、最多 3 次重定向且每跳重新检查、正文与解压后均有上限。
 * 不信任系统代理，不提供任何测试绕过开关。错误信息不回显完整上游 URL/响应。
 */
@Component
public class SafeUrlReader {
    public static final int MAX_REDIRECTS = 3;
    public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final java.util.concurrent.ScheduledThreadPoolExecutor HANDSHAKE_DEADLINES =
            new java.util.concurrent.ScheduledThreadPoolExecutor(1,
                    task -> Thread.ofPlatform().daemon().name("notice-tls-deadline").unstarted(task));
    static { HANDSHAKE_DEADLINES.setRemoveOnCancelPolicy(true); }

    public static final class FetchFailure extends RuntimeException {
        private final String code;
        private final boolean retryable;
        public FetchFailure(String code, boolean retryable, String message) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }

    public record Fetched(String body, String contentType) {}

    private final SSLContext sslContext;
    private final javax.net.ssl.HostnameVerifier hostnameVerifier;

    /** 生产默认：系统信任库 + 默认主机名校验。 */
    public SafeUrlReader() { this(null, null); }

    /** 测试注入：受控信任边界；生产不提供测试绕过开关。 */
    public SafeUrlReader(SSLContext sslContext, javax.net.ssl.HostnameVerifier hostnameVerifier) {
        this.sslContext = sslContext;
        this.hostnameVerifier = hostnameVerifier;
    }

    public Fetched fetch(String url) {
        return fetch(url, 15_000);
    }

    /** Absolute deadline covers DNS validation, connect, TLS, headers and body. */
    public Fetched fetch(String url, long timeoutMillis) {
        if (url == null || url.length() > 1024) throw new FetchFailure("URL_INVALID", false, "原文链接不合法");
        if (timeoutMillis <= 0 || timeoutMillis > 120_000) timeoutMillis = 15_000;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Target target = validateTarget(url);
        String body = fetch(target, 0, deadline);
        return new Fetched(body, null);
    }

    private String fetch(Target target, int hops, long deadline) {
        try (SSLSocket socket = connect(target, deadline)) {
            write(socket, "GET " + target.path() + " HTTP/1.1\r\n"
                    + "Host: " + target.host() + "\r\n"
                    + "Connection: close\r\n"
                    + "Accept: text/html,text/plain,application/json,application/xml;q=0.9,*/*;q=0.1\r\n"
                    + "Accept-Encoding: gzip, deflate\r\n"
                    + "User-Agent: caizhitong-notice-fetcher/1.0\r\n\r\n");
            socket.getOutputStream().flush();
            HeaderBlock headers = readHeaders(socket, deadline);
            String[] status = headers.statusLine.split(" ", 3);
            int code = Integer.parseInt(status[1]);
            String location = headers.headers.stream()
                    .filter(line -> line.toLowerCase().startsWith("location:"))
                    .map(line -> line.substring("location:".length()).trim())
                    .findFirst().orElse(null);
            if (code >= 300 && code < 400 && location != null) {
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308)
                    throw new FetchFailure("REDIRECT_INVALID", false, "原文链接跳转类型不受支持");
                if (hops + 1 > MAX_REDIRECTS) throw new FetchFailure("TOO_MANY_REDIRECTS", false, "原文链接跳转次数过多");
                String next = resolve(target.url(), location);
                Target nextTarget = validateTarget(next); // 每跳重新解析与检查，且仅 HTTPS
                return fetch(nextTarget, hops + 1, deadline);
            }
            if (code != 200) throw new FetchFailure("UPSTREAM_STATUS", code == 429 || code >= 500, "原文链接返回异常状态");
            String contentType = headers.headers.stream()
                    .filter(line -> line.toLowerCase().startsWith("content-type:"))
                    .map(line -> line.substring("content-type:".length()).trim())
                    .findFirst().orElse("");
            if (!acceptableText(contentType)) throw new FetchFailure("CONTENT_TYPE", false, "原文链接不是可处理的文本类型");
            String encoding = headers.headers.stream()
                    .filter(line -> line.toLowerCase().startsWith("content-encoding:"))
                    .map(line -> line.substring("content-encoding:".length()).trim().toLowerCase())
                    .findFirst().orElse(null);
            byte[] body = readBody(socket, headers, encoding, deadline);
            return new String(body, StandardCharsets.UTF_8);
        } catch (FetchFailure e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            throw new FetchFailure("TIMEOUT", true, "原文链接读取超时");
        } catch (IOException e) {
            if (System.nanoTime() >= deadline) throw new FetchFailure("TIMEOUT", true, "原文链接读取超时");
            throw new FetchFailure("UPSTREAM_IO", true, "原文链接读取失败");
        }
    }

    /** 创建时的 URL 结构 + 地址校验（worker 访问前会再次校验）。 */
    public void validate(String url) { validateTarget(url); }

    /** 重定向目标逐跳复验：按新 URL 完整校验（禁止降级/内网/非 443）。 */
    Target validateRedirect(String base, String location) {
        return validateTarget(resolve(base, location));
    }

    /** URL 结构 + 全部地址解析校验；返回固定目标（已验证 IP + 原主机）。 */
    private Target validateTarget(String url) {
        URI uri;
        try { uri = new URI(url); } catch (URISyntaxException e) { throw new FetchFailure("URL_INVALID", false, "原文链接不合法"); }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new FetchFailure("URL_SCHEME", false, "原文链接必须为 HTTPS");
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank())
            throw new FetchFailure("URL_INVALID", false, "原文链接不合法");
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new FetchFailure("URL_PORT", false, "原文链接端口不受支持");
        if (uri.getFragment() != null || uri.getQuery() != null && uri.getQuery().isEmpty())
            throw new FetchFailure("URL_INVALID", false, "原文链接不合法");
        String host = uri.getHost();
        List<InetAddress> addresses;
        try { addresses = List.of(InetAddress.getAllByName(host)); }
        catch (UnknownHostException e) { throw new FetchFailure("DNS_FAILED", true, "原文链接域名无法解析"); }
        if (addresses.isEmpty()) throw new FetchFailure("DNS_FAILED", true, "原文链接域名无法解析");
        for (InetAddress address : addresses) checkAddress(address);
        InetAddress pinned = addresses.getFirst();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();
        return new Target(url, host, pinned, path);
    }

    /** 拒内网、环回、链路本地、保留/未指定与 IPv4 映射 IPv6 绕过。 */
    private void checkAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            // Only native global unicast is eligible; conservatively reject transition/special ranges.
            if ((bytes[0] & 0xE0) != 0x20) throw block();                                    // outside 2000::/3
            if (bytes[0] == 0x20 && bytes[1] == 0x02) throw block();                          // 6to4 embeds IPv4
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && (bytes[2] & 0xFE) == 0) throw block(); // 2001::/23 special
            if (bytes[0] == 0x3F && bytes[1] == (byte) 0xFF && (bytes[2] & 0xF0) == 0) throw block(); // 3fff::/20 documentation
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0D && bytes[3] == (byte) 0xB8) throw block(); // 2001:db8::/32 文档
            return;
        }
        if (bytes.length == 4) { checkV4(bytes); return; }
        throw block();
    }

    private void checkV4(byte[] bytes) {
        int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF, b2 = bytes[2] & 0xFF;
        if (b0 == 0 || b0 == 10 || b0 == 127) throw block();                                  // 0/8, 10/8, 127/8
        if (b0 == 100 && (b1 & 0xC0) == 64) throw block();                                    // 100.64/10 CGN
        if (b0 == 169 && b1 == 254) throw block();                                            // link-local
        if (b0 == 172 && b1 >= 16 && b1 <= 31) throw block();                                 // 172.16/12
        if (b0 == 192 && b1 == 168) throw block();                                            // 192.168/16
        if (b0 == 192 && b1 == 0) throw block();                                              // 192.0.0/24, 192.0.2/24
        if (b0 == 198 && (b1 == 18 || b1 == 19)) throw block();                               // 198.18/15 基准测试
        if (b0 == 198 && b1 == 51 && b2 == 100) throw block();                                // 198.51.100/24 文档
        if (b0 == 203 && b1 == 0 && b2 == 113) throw block();                                 // 203.0.113/24 文档
        if (b0 >= 224) throw block();                                                         // 组播/保留/广播
    }

    private FetchFailure block() { return new FetchFailure("URL_BLOCKED", false, "原文链接指向不可访问的地址"); }

    private SSLSocket connect(Target target, long deadline) throws IOException {
        SSLContext context;
        try { context = sslContext != null ? sslContext : SSLContext.getDefault(); }
        catch (Exception e) { throw new FetchFailure("TLS_FAILED", true, "安全连接不可用"); }
        SSLSocketFactory factory = context.getSocketFactory();
        java.net.Socket transport = new java.net.Socket();
        SSLSocket socket = null;
        var expired = new java.util.concurrent.atomic.AtomicBoolean();
        // SO_TIMEOUT bounds each read, not a multi-record TLS handshake. Close at the absolute deadline too.
        var deadlineClose = HANDSHAKE_DEADLINES.schedule(() -> {
            expired.set(true);
            try { transport.close(); } catch (IOException ignored) { }
        }, Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
        try {
            transport.connect(new InetSocketAddress(target.address(), 443), Math.min(2000, remainingMillis(deadline)));
            // Connect to the verified IP, but bind JSSE peer identity and SNI to the logical URL host.
            socket = (SSLSocket) factory.createSocket(transport, target.host(), 443, true);
            SSLParameters parameters = context.getDefaultSSLParameters();
            if (!target.host().contains(":")) parameters.setServerNames(List.of(new SNIHostName(target.host())));
            if (hostnameVerifier == null) parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(remainingMillis(deadline));
            socket.startHandshake();
            if (hostnameVerifier != null && !hostnameVerifier.verify(target.host(), socket.getSession()))
                throw new FetchFailure("TLS_HOST", false, "原文链接证书与主机不匹配");
            remainingMillis(deadline);
            return socket;
        } catch (IOException | RuntimeException failure) {
            try { if (socket != null) socket.close(); else transport.close(); } catch (IOException ignored) { }
            if (expired.get() || System.nanoTime() >= deadline) {
                var timeout = new java.net.SocketTimeoutException("handshake deadline exceeded");
                timeout.initCause(failure);
                throw timeout;
            }
            throw failure;
        } finally {
            deadlineClose.cancel(false);
        }
    }

    private HeaderBlock readHeaders(SSLSocket socket, long deadline) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (buffer.size() < MAX_HEADER_BYTES) {
            int read = readOne(socket, deadline);
            if (read < 0) break;
            buffer.write(read);
            byte[] bytes = buffer.toByteArray();
            if (bytes.length >= 4 && bytes[bytes.length - 4] == '\r' && bytes[bytes.length - 3] == '\n'
                    && bytes[bytes.length - 2] == '\r' && bytes[bytes.length - 1] == '\n') {
                String head = new String(bytes, 0, bytes.length - 4, StandardCharsets.ISO_8859_1);
                String[] lines = head.split("\r\n");
                if (lines.length == 0 || !lines[0].startsWith("HTTP/1.")) throw new FetchFailure("PROTOCOL", false, "原文链接协议响应异常");
                List<String> rest = new ArrayList<>();
                for (int i = 1; i < lines.length; i++) rest.add(lines[i]);
                return new HeaderBlock(lines[0], rest);
            }
        }
        throw new FetchFailure("HEADER_TOO_LARGE", false, "原文链接响应头过大");
    }

    private byte[] readBody(SSLSocket socket, HeaderBlock headers, String encoding, long deadline) throws IOException {
        InputStream input = socket.getInputStream();
        String length = header(headers, "content-length");
        String transfer = header(headers, "transfer-encoding");
        Long contentLength = null;
        if (length != null) {
            if (!length.matches("[0-9]+")) throw protocol();
            try { contentLength = Long.parseLong(length); } catch (NumberFormatException e) { throw protocol(); }
            if (contentLength > MAX_BODY_BYTES) throw tooLarge();
        }
        if (transfer != null && (!transfer.equalsIgnoreCase("chunked") || length != null)) throw protocol();
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        if (transfer != null) {
            while (true) {
                String sizeLine = readLine(input, socket, deadline);
                if (sizeLine == null) throw protocol();
                String hex = sizeLine.split(";", 2)[0];
                if (!hex.matches("[0-9a-fA-F]{1,8}")) throw protocol();
                long size = Long.parseLong(hex, 16);
                if (size > MAX_BODY_BYTES - framed.size()) throw tooLarge();
                if (size == 0) {
                    int trailerBytes = 0;
                    while (true) {
                        String trailer = readLine(input, socket, deadline);
                        if (trailer == null) throw protocol();
                        trailerBytes += trailer.length() + 2;
                        if (trailerBytes > MAX_HEADER_BYTES) throw protocol();
                        if (trailer.isEmpty()) break;
                        if (!trailer.contains(":")) throw protocol();
                    }
                    break;
                }
                copy(input, framed, socket, deadline, size);
                if (readOne(input, socket, deadline) != '\r' || readOne(input, socket, deadline) != '\n') throw protocol();
            }
        } else {
            copy(input, framed, socket, deadline, contentLength == null ? -1 : contentLength);
        }
        // Content-Length and chunk framing describe compressed octets, never decoded characters.
        InputStream decoded = new java.io.ByteArrayInputStream(framed.toByteArray());
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
            if (encoding.equalsIgnoreCase("gzip")) decoded = new GZIPInputStream(decoded);
            else if (encoding.equalsIgnoreCase("deflate")) decoded = new InflaterInputStream(decoded);
            else throw protocol();
        }
        try (InputStream content = decoded) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            copy(content, body, socket, deadline, -1);
            return body.toByteArray();
        }
    }

    private String header(HeaderBlock headers, String name) {
        var values = headers.headers.stream().filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith(name + ":"))
                .map(line -> line.substring(name.length() + 1).trim()).toList();
        if (values.size() > 1) throw protocol();
        return values.isEmpty() ? null : values.getFirst();
    }

    private void copy(InputStream input, ByteArrayOutputStream output, SSLSocket socket, long deadline, long expected) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = expected;
        while (remaining != 0) {
            socket.setSoTimeout(remainingMillis(deadline));
            int read = input.read(buffer, 0, remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining));
            remainingMillis(deadline);
            if (read < 0) {
                if (remaining > 0) throw protocol();
                return;
            }
            if (output.size() + read > MAX_BODY_BYTES) throw tooLarge();
            output.write(buffer, 0, read);
            if (remaining > 0) remaining -= read;
        }
    }

    private FetchFailure protocol() { return new FetchFailure("PROTOCOL", false, "原文链接协议响应异常"); }
    private FetchFailure tooLarge() { return new FetchFailure("BODY_TOO_LARGE", false, "原文链接正文过大"); }

    private int remainingMillis(long deadline) throws java.net.SocketTimeoutException {
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0) throw new java.net.SocketTimeoutException("read deadline exceeded");
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private String readLine(InputStream input, SSLSocket socket, long deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < MAX_HEADER_BYTES) {
            int read = readOne(input, socket, deadline);
            if (read < 0) { if (line.size() == 0) return null; throw protocol(); }
            if (read == '\n') {
                byte[] bytes = line.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') throw protocol();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(read);
        }
        throw new FetchFailure("HEADER_TOO_LARGE", false, "原文链接响应头过大");
    }

    private int readOne(SSLSocket socket, long deadline) throws IOException {
        return readOne(socket.getInputStream(), socket, deadline);
    }

    private int readOne(InputStream input, SSLSocket socket, long deadline) throws IOException {
        socket.setSoTimeout(remainingMillis(deadline));
        return input.read();
    }

    private boolean acceptableText(String contentType) {
        String type = contentType.split(";")[0].trim().toLowerCase();
        if (type.isEmpty()) return true;
        return type.startsWith("text/") || type.equals("application/json") || type.equals("application/xml")
                || type.endsWith("+json") || type.endsWith("+xml");
    }

    private String resolve(String base, String location) {
        try { return URI.create(base).resolve(location).toString(); }
        catch (RuntimeException e) { throw new FetchFailure("REDIRECT_INVALID", false, "原文链接跳转地址不合法"); }
    }

    private void write(SSLSocket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private record Target(String url, String host, InetAddress address, String path) {}
    private record HeaderBlock(String statusLine, List<String> headers) {}
}
