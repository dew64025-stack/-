package com.caizhitong.integration.scanner;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Exact bounded INSTREAM exchange; unknown and incomplete responses never become clean. */
public final class ClamdScanner implements VirusScanner {
    private final String host;
    private final int port;
    public ClamdScanner(String host,int port){this.host=host;this.port=port;}
    @Override public ScanResult scan(InputStream input,long maxBytes){
        if(host==null||host.isBlank()||port<1||port>65535||maxBytes<1||maxBytes>20L*1024*1024)return ScanResult.FAILED;
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(host,port),2000);socket.setSoTimeout(30000);
            // A write timeout is not available on java.net.Socket: an absolute deadline closes blocked IO too.
            try(var deadline=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
                var timer=deadline.schedule(()->{try{socket.close();}catch(IOException ignored){}},32,java.util.concurrent.TimeUnit.SECONDS);
                try {
                    DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                    byte[] chunk=new byte[8192];long count=0;int n;
                    while((n=input.read(chunk))!=-1){if(n==0)continue;count+=n;if(count>maxBytes)return ScanResult.FAILED;out.writeInt(n);out.write(chunk,0,n);}
                    out.writeInt(0);out.flush();
                    ByteArrayOutputStream response=new ByteArrayOutputStream();InputStream in=socket.getInputStream();boolean ended=false;
                    for(int i=0;i<=4096;i++){int b=in.read();if(b<0)break;if(b==0){ended=true;break;}if(i==4096)return ScanResult.FAILED;if(b<32||b>126)return ScanResult.FAILED;response.write(b);}
                    if(!ended)return ScanResult.FAILED;
                    String value=response.toString(StandardCharsets.US_ASCII);
                    if(value.equals("stream: OK"))return ScanResult.CLEAN;
                    if(value.matches("stream: [A-Za-z0-9_.() -]{1,256} FOUND"))return ScanResult.INFECTED;
                    return ScanResult.FAILED;
                } finally {timer.cancel(false);}
            }
        }catch(IOException|RuntimeException e){return ScanResult.FAILED;}
    }
}
