package com.caizhitong.integration.notice;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real local TLS and ephemeral keys; only the validated target is supplied reflectively. */
class SafeUrlReaderTlsTest {
    @TempDir static Path temporary;
    private static SSLContext serverContext;
    private static SSLContext trustedClient;
    private static SSLContext untrustedClient;

    @BeforeAll
    static void ephemeralCertificate() throws Exception {
        Path keyStorePath = temporary.resolve("peer.p12");
        String password = java.util.UUID.randomUUID().toString();
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
        if (!Files.exists(keytool)) keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        var process = new ProcessBuilder(keytool.toString(),
                "-genkeypair", "-alias", "peer", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=notice.fixture.test", "-ext", "SAN=dns:notice.fixture.test",
                "-storetype", "PKCS12", "-keystore", keyStorePath.toString(), "-storepass", password,
                "-keypass", password, "-noprompt").redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "ephemeral keytool timed out");
        assertEquals(0, process.exitValue(), "ephemeral keytool failed");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) { keys.load(input, password.toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password.toCharArray());
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("peer", keys.getCertificate("peer"));
        trustedClient = client(trust);
        untrustedClient = SSLContext.getInstance("TLS");
        untrustedClient.init(null, null, null); // Ordinary system trust does not trust the ephemeral peer.
    }

    private static SSLContext client(KeyStore trust) throws Exception {
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    @Test
    void productionHostnameValidationAcceptsTrustedMatchingPeerAndReadsBody() throws Exception {
        assertEquals("trusted notice", fetch("notice.fixture.test", trustedClient));
    }

    @Test
    void productionHostnameValidationRejectsTrustedWrongHostname() throws Exception {
        assertThrows(SafeUrlReader.FetchFailure.class, () -> fetch("wrong.fixture.test", trustedClient));
    }

    @Test
    void productionTrustValidationRejectsUntrustedMatchingPeer() throws Exception {
        assertThrows(SafeUrlReader.FetchFailure.class, () -> fetch("notice.fixture.test", untrustedClient));
    }

    private String fetch(String hostname, SSLContext context) throws Exception {
        try (var server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var transport = new java.net.Socket()) {
            server.setSoTimeout(5000);
            var served = CompletableFuture.runAsync(() -> {
                try (var socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                            java.nio.charset.StandardCharsets.ISO_8859_1));
                    for (String line; (line = input.readLine()) != null && !line.isEmpty();) { }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                            + "Content-Length: 14\r\nConnection: close\r\n\r\ntrusted notice")
                            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                } catch (java.io.IOException expectedWhenClientRejects) { }
            });
            try (var routed = org.mockito.Mockito.mockConstruction(java.net.Socket.class,
                    ignored -> org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(transport)),
                    (socket, ignored) -> org.mockito.Mockito.doAnswer(call -> {
                        assertEquals(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(),443), call.getArgument(0));
                        transport.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()), call.getArgument(1));
                        return null;
                    }).when(socket).connect(org.mockito.Mockito.any(java.net.SocketAddress.class),org.mockito.Mockito.anyInt()))) {
                Class<?> targetClass = Class.forName(SafeUrlReader.class.getName() + "$Target");
                var constructor = targetClass.getDeclaredConstructor(String.class, String.class, InetAddress.class, String.class);
                constructor.setAccessible(true);
                Object target = constructor.newInstance("https://" + hostname + "/", hostname,
                        InetAddress.getLoopbackAddress(), "/");
                var method = SafeUrlReader.class.getDeclaredMethod("fetch", targetClass, int.class, long.class);
                method.setAccessible(true);
                try {
                    // Null verifier follows exactly the production hostname-validation branch.
                    return (String) method.invoke(new SafeUrlReader(context, null), target, 0,
                            System.nanoTime() + Duration.ofSeconds(5).toNanos());
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    if (failure.getCause() instanceof Exception cause) throw cause;
                    throw failure;
                }
            } finally { served.get(6, TimeUnit.SECONDS); }
        }
    }
}
