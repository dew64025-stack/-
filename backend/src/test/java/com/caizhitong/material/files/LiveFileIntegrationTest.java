package com.caizhitong.material.files;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.integration.scanner.*;
import com.caizhitong.integration.storage.ObjectStorage;
import com.caizhitong.security.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Explicit opt-in: real private S3, real ClamD and real Redis; business SQL remains isolated H2. */
@EnabledIfEnvironmentVariable(named="CZT_LIVE_FILES_TEST",matches="true")
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:live_files;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc @ActiveProfiles("test")
@Sql(scripts={"/identity-schema.sql","/files-schema.sql"},executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class LiveFileIntegrationTest {
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r){
        r.add("spring.data.redis.port",()->16379);r.add("spring.data.redis.password",()->required("CZT_LIVE_REDIS_PASSWORD"));
        r.add("app.files.s3.endpoint",()->"http://127.0.0.1:13900");r.add("app.files.s3.bucket",()->"caizhitong-dev-private");
        r.add("app.files.s3.access-key",()->required("CZT_LIVE_S3_ACCESS_KEY"));r.add("app.files.s3.secret-key",()->required("CZT_LIVE_S3_SECRET_KEY"));
        r.add("app.files.clamd.host",()->"127.0.0.1");r.add("app.files.clamd.port",()->13310);
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Live test credentials missing");return value;}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    @Autowired FileScanWorker scan;
    @Autowired FileCleanupWorker cleanup;
    @Autowired ObjectStorage storage;
    @Autowired VirusScanner scanner;
    @Autowired SensitiveDataCrypto crypto;
    @Test void realPrivateUploadScanSignatureAndCleanupAndEicarDetection()throws Exception {
        jdbc.update("INSERT INTO campuses VALUES(1,'测试','live','active',NULL)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at) VALUES(101,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        String access=sessions.issue(101,1,SessionKind.STUDENT).accessToken(),bearer="Bearer "+access;var mapper=new JsonMapper();
        var original=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",original);
        byte[] bytes=original.toByteArray();String body=mapper.writeValueAsString(Map.of("purpose","avatar","fileName","live.png","mimeType","image/png","sizeBytes",bytes.length,"sha256",FileFormat.sha256(bytes)));
        List<String> keys=new ArrayList<>();
        try {
            var intent=mapper.readTree(mvc.perform(post("/api/v1/files/upload-intents").header("Authorization",bearer).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            String upload=intent.path("uploadId").asString();
            keys.add(crypto.decrypt("file-object-key",jdbc.queryForObject("SELECT object_key_encrypted FROM file_uploads WHERE id=?",byte[].class,Long.parseLong(upload))));
            mvc.perform(multipart("/api/v1/files/uploads/"+upload).file(new org.springframework.mock.web.MockMultipartFile("file","live.png","image/png",bytes)).header("Authorization","Upload "+intent.path("uploadToken").asString())).andExpect(status().isCreated());
            String result=mvc.perform(post("/api/v1/files/uploads/"+upload+"/complete").header("Authorization",bearer).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            String attachment=mapper.readTree(result).path("data").path("attachmentId").asString();scan.run();
            assertEquals("clean",jdbc.queryForObject("SELECT scan_status FROM file_attachments WHERE id=?",String.class,Long.parseLong(attachment)));
            keys.add(crypto.decrypt("file-object-key",jdbc.queryForObject("SELECT object_key_encrypted FROM file_attachments WHERE id=?",byte[].class,Long.parseLong(attachment))));
            String download=mvc.perform(post("/api/v1/files/"+attachment+"/download-url").header("Authorization",bearer).contentType("application/json").content("{}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            URI signed=URI.create(mapper.readTree(download).path("data").path("url").asString());
            try(var http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
                var response=http.send(HttpRequest.newBuilder(signed).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode());assertEquals(3,javax.imageio.ImageIO.read(new ByteArrayInputStream(response.body())).getWidth());
                URI anonymous=new URI(signed.getScheme(),signed.getAuthority(),signed.getPath(),null,null);
                assertEquals(403,http.send(HttpRequest.newBuilder(anonymous).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            assertEquals(ScanResult.CLEAN,scanner.scan(new ByteArrayInputStream(bytes),bytes.length));
            byte[] eicar=("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);
            assertEquals(ScanResult.INFECTED,scanner.scan(new ByteArrayInputStream(eicar),eicar.length));
            cleanup.run();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_cleanup_jobs WHERE state='succeeded'",Integer.class));
        }finally {
            for(String key:keys)storage.delete(key);
            sessions.revoke(access,SessionKind.STUDENT);
        }
    }
}
