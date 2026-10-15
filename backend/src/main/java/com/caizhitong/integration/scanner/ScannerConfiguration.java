package com.caizhitong.integration.scanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
@Configuration public class ScannerConfiguration {
    @Bean public VirusScanner virusScanner(@Value("${app.files.clamd.host:}")String host,@Value("${app.files.clamd.port:3310}")int port){return new ClamdScanner(host,port);}
}
