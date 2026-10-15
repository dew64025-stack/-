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
import javax.net.ssl.HttpsURLConnection;
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
        if (url == null || url.length() > 1024) throw new FetchFailure("URL_INVALID", false, "原文链接不合法");
        Target target = validateTarget(url);
        String body = fetch(target, 0);
        return new Fetched(body, null);
    }

    private String fetch(Target target, int hops) {
        long deadline = System.nanoTime() + 15_000_000_000L;
        return fetch(target, hops, deadline);
    }

    private String fetch(Target target, int hops, long deadline) {
        try (SSLSocket socket = connect(target)) {
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
            throw new FetchFailure("UPSTREAM_IO", true, "原文链接读取失败");
        }
    }

    /** 创建时的 URL 结构 + 地址校验（worker 访问前会再次校验）。 */
    public void validate(String url) { validateTarget(url); }

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
            boolean mappedV4 = true;
            for (int i = 0; i < 10; i++) if (bytes[i] != 0) { mappedV4 = false; break; }
            if (mappedV4 && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                byte[] v4 = new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
                checkV4(v4);
                return;
            }
            if (bytes[0] == (byte) 0xFE && (bytes[1] & 0xC0) == 0x80) throw block();          // fe80::/10 link-local
            if ((bytes[0] & 0xFE) == (byte) 0xFC) throw block();                              // fc00::/7 ULA
            boolean zero = true;
            for (byte b : bytes) if (b != 0) { zero = false; break; }
            if (zero) throw block();                                                           // ::
            if (bytes[15] == 1 && zeroExcept(bytes, 15)) throw block();                       // ::1
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

    private boolean zeroExcept(byte[] bytes, int except) {
        for (int i = 0; i < bytes.length; i++) if (i != except && bytes[i] != 0) return false;
        return true;
    }

    private SSLSocket connect(Target target) throws IOException {
        SSLContext context;
        try { context = sslContext != null ? sslContext : SSLContext.getDefault(); }
        catch (Exception e) { throw new FetchFailure("TLS_FAILED", true, "安全连接不可用"); }
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        SSLParameters parameters = context.getDefaultSSLParameters();
        parameters.setServerNames(List.of(new SNIHostName(target.host())));
        socket.setSSLParameters(parameters);
        socket.connect(new InetSocketAddress(target.address(), 443), 2000);
        socket.startHandshake();
        javax.net.ssl.HostnameVerifier verifier = hostnameVerifier != null ? hostnameVerifier : HttpsURLConnection.getDefaultHostnameVerifier();
        if (!verifier.verify(target.host(), socket.getSession()))
            throw new FetchFailure("TLS_HOST", false, "原文链接证书与主机不匹配");
        return socket;
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
        if (encoding != null && encoding.contains("gzip")) input = new GZIPInputStream(input);
        else if (encoding != null && encoding.contains("deflate")) input = new InflaterInputStream(input);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Long contentLength = headers.headers.stream()
                .filter(line -> line.toLowerCase().startsWith("content-length:"))
                .map(line -> { try { return Long.parseLong(line.substring("content-length:".length()).trim()); } catch (RuntimeException e) { return null; } })
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        boolean chunked = headers.headers.stream().anyMatch(line -> line.toLowerCase().startsWith("transfer-encoding:")
                && line.toLowerCase().contains("chunked"));
        long remaining = contentLength != null ? Math.min(contentLength, MAX_BODY_BYTES + 1L) : MAX_BODY_BYTES + 1L;
        if (chunked) {
            while (true) {
                String sizeLine = readLine(input, socket, deadline);
                if (sizeLine == null) break;
                int size = Integer.parseInt(sizeLine.trim().split(";")[0], 16);
                if (size == 0) { readLine(input, socket, deadline); break; }
                for (int i = 0; i < size; i++) {
                    int read = readOne(input, socket, deadline);
                    if (read < 0) break;
                    body.write(read);
                    if (body.size() > MAX_BODY_BYTES) throw new FetchFailure("BODY_TOO_LARGE", false, "原文链接正文过大");
                }
                readLine(input, socket, deadline);
            }
        } else {
            while (remaining-- > 0) {
                int read = readOne(input, socket, deadline);
                if (read < 0) break;
                body.write(read);
                if (body.size() > MAX_BODY_BYTES) throw new FetchFailure("BODY_TOO_LARGE", false, "原文链接正文过大");
            }
        }
        return body.toByteArray();
    }

    private String readLine(InputStream input, SSLSocket socket, long deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < MAX_HEADER_BYTES) {
            int read = readOne(input, socket, deadline);
            if (read < 0) return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
            if (read == '\n') return line.toString(StandardCharsets.ISO_8859_1).replace("\r", "");
            line.write(read);
        }
        throw new FetchFailure("HEADER_TOO_LARGE", false, "原文链接响应头过大");
    }

    private int readOne(SSLSocket socket, long deadline) throws IOException {
        return readOne(socket.getInputStream(), socket, deadline);
    }

    private int readOne(InputStream input, SSLSocket socket, long deadline) throws IOException {
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0) throw new java.net.SocketTimeoutException("read deadline exceeded");
        socket.setSoTimeout((int) remaining);
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
