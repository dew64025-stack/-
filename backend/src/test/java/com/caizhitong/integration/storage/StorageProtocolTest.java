package com.caizhitong.integration.storage;

import com.caizhitong.common.BusinessException;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageProtocolTest {
    @Test void redirectIsNotFollowedAndMissingConfigurationNeverReturnsFakeUrl()throws Exception {
        var hits=new AtomicInteger();var target=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        target.createContext("/",exchange->{hits.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});target.start();
        var redirect=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        redirect.createContext("/",exchange->{exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+target.getAddress().getPort()+"/unexpected");exchange.sendResponseHeaders(302,-1);exchange.close();});redirect.start();
        try {
            var storage=new StorageConfiguration().objectStorage("http://127.0.0.1:"+redirect.getAddress().getPort(),"test-access","test-secret","private-test","us-east-1");
            assertThrows(BusinessException.class,()->storage.put("quarantine/server-key",new ByteArrayInputStream(new byte[]{1,2,3}),"image/png"));assertEquals(0,hits.get());
            var missing=new StorageConfiguration().objectStorage("","","","","us-east-1");
            assertEquals(503,assertThrows(BusinessException.class,()->missing.sign("server-key",Duration.ofSeconds(120))).getStatus());
        } finally {redirect.stop(0);target.stop(0);}
    }
}
