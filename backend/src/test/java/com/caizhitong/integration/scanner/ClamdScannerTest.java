package com.caizhitong.integration.scanner;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ClamdScannerTest {
    @Test void exactOkAndFoundAreAcceptedButMalformedErrorDisconnectAndOversizeFail()throws Exception {
        assertEquals(ScanResult.CLEAN,exchange("stream: OK\0"));
        assertEquals(ScanResult.INFECTED,exchange("stream: Eicar-Signature FOUND\0"));
        for(String response:new String[]{"other: OK\0","stream: OK ERROR\0","stream: ERROR\0","stream: OK","x".repeat(4097)+"\0","stream: OK\n\0",""})assertEquals(ScanResult.FAILED,exchange(response),response.substring(0,Math.min(20,response.length())));
        assertEquals(ScanResult.FAILED,new ClamdScanner("",3310).scan(new ByteArrayInputStream(new byte[]{1}),1));
    }
    private ScanResult exchange(String response)throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var pool=Executors.newSingleThreadExecutor()){
            Future<byte[]> received=pool.submit(()->{
                try(var socket=server.accept()){
                    socket.setSoTimeout(2000);var input=new DataInputStream(socket.getInputStream());
                    assertArrayEquals("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII),input.readNBytes(10));
                    var content=new ByteArrayOutputStream();int size;while((size=input.readInt())!=0){assertTrue(size<=8192);content.write(input.readNBytes(size));}
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));return content.toByteArray();
                }
            });
            ScanResult result=new ClamdScanner("127.0.0.1",server.getLocalPort()).scan(new ByteArrayInputStream(new byte[]{4,5,6}),3);
            assertArrayEquals(new byte[]{4,5,6},received.get(3,TimeUnit.SECONDS));return result;
        }
    }
}
