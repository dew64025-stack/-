package com.caizhitong.integration.wechat;

import com.caizhitong.common.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class JavaWechatTransportTest {
    @Test void realLocalHttpRunsProductionBodySubscriberAndBlocksRedirectOversizeAndTimeout()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/sns/jscode2session",exchange->{byte[] body="{\"openid\":\"actual-http-openid\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/oversize",exchange->{byte[] body=new byte[262145];exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","https://invalid.example/secret");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/slow",exchange->{try{Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();});
        server.start();
        try(HttpClient delegate=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()){
            var transport=new JavaWechatTransport(new LocalClient(delegate,server.getAddress().getPort()));
            assertEquals("actual-http-openid",new WechatHttpGateway("test-app","test-secret",transport,new JsonMapper()).login("test-code").openid());
            assertEquals(302,transport.send(request("/redirect",Duration.ofSeconds(8))).status());
            assertEquals(502,assertThrows(BusinessException.class,()->transport.send(request("/oversize",Duration.ofSeconds(8)))).getStatus());
            assertEquals(502,assertThrows(BusinessException.class,()->transport.send(request("/slow",Duration.ofMillis(50)))).getStatus());
            assertThrows(BusinessException.class,()->transport.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1/forbidden")).GET().build()));
        }finally{server.stop(0);}
    }
    private HttpRequest request(String path,Duration timeout){return HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com"+path)).timeout(timeout).GET().build();}
    /** Only this test client rewrites the fixed trusted URI to the loopback HTTP fixture. */
    private static final class LocalClient extends HttpClient {
        final HttpClient delegate;final int port;
        LocalClient(HttpClient delegate,int port){this.delegate=delegate;this.port=port;}
        public <T>CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){assertEquals("api.weixin.qq.com",request.uri().getHost());var local=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+request.uri().getRawPath()+"?"+Objects.toString(request.uri().getRawQuery(),""))).timeout(request.timeout().orElse(Duration.ofSeconds(8))).method(request.method(),request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).build();return delegate.sendAsync(local,handler);}
        public <T>CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException();}
        public <T>HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException();}
        public Optional<CookieHandler> cookieHandler(){return delegate.cookieHandler();}public Optional<Duration> connectTimeout(){return delegate.connectTimeout();}public Redirect followRedirects(){return delegate.followRedirects();}public Optional<ProxySelector> proxy(){return delegate.proxy();}public SSLContext sslContext(){return delegate.sslContext();}public SSLParameters sslParameters(){return delegate.sslParameters();}public Optional<Authenticator> authenticator(){return delegate.authenticator();}public Version version(){return delegate.version();}public Optional<Executor> executor(){return delegate.executor();}
    }
}
