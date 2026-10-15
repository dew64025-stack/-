package com.caizhitong.material.files;

import com.caizhitong.common.*;
import com.caizhitong.common.ratelimit.RateLimiter;
import com.caizhitong.integration.storage.ObjectStorage;
import com.caizhitong.security.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class UploadLifecycleTest {
    final MutableClock clock=new MutableClock();
    final LateStore storage=new LateStore();
    JdbcTemplate jdbc;
    UploadService uploads;
    FileCleanupWorker cleanup;
    Actor actor=new Actor(101,1,Set.of(),false,false,"active",SessionKind.STUDENT);
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:upload_lifecycle_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("identity-schema.sql"),new ClassPathResource("files-schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source);var tm=new DataSourceTransactionManager(source);byte[] hmac=new byte[32];hmac[0]=1;var crypto=new SensitiveDataCrypto(new byte[32],hmac);
        uploads=new UploadService(jdbc,crypto,new SnowflakeIds(clock,1),clock,tm,new RateLimiter((bucket,window)->1L),(user,campus,kind)->Optional.of(actor),storage);
        cleanup=new FileCleanupWorker(jdbc,storage,crypto,clock,tm);
    }
    @Test void multipartFinishingAfterCleanupCannotStartObjectWrite()throws Exception {
        var claim=claim();var parsed=new CountDownLatch(1);var parsing=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var receiver=pool.submit(()->{parsing.countDown();assertTrue(parsed.await(5,TimeUnit.SECONDS));return receiveStatus(claim,new MockMultipartFile("file","test","image/png",new byte[]{1,2,3}));});
            assertTrue(parsing.await(5,TimeUnit.SECONDS));clock.advance(Duration.ofMinutes(11));cleanup.run();
            assertEquals("succeeded",jdbc.queryForObject("SELECT state FROM file_cleanup_jobs",String.class));
            parsed.countDown();assertEquals(409,receiver.get(5,TimeUnit.SECONDS));
        }finally{parsed.countDown();}
        assertEquals(0,storage.writes);assertTrue(storage.objects.isEmpty());
    }
    @Test void objectWriteFinishingAfterCleanupIsFencedAndReliablyCleanedAgain()throws Exception {
        verifyLateWrite(false);
    }
    @Test void partialObjectWriteThrowingAfterCleanupStillRegistersReliableCleanup()throws Exception {
        verifyLateWrite(true);
    }
    private void verifyLateWrite(boolean failAfterWrite)throws Exception {
        var claim=claim();storage.failAfterWrite=failAfterWrite;var file=new MockMultipartFile("file","test","image/png",new byte[]{1,2,3}){
            @Override public InputStream getInputStream(){return new ByteArrayInputStream(new byte[]{1,2,3}){
                boolean first=true;
                @Override public synchronized int read(byte[] b,int off,int len){if(first){first=false;storage.reading.countDown();try{if(!storage.resume.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Blocked upload was not released");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return super.read(b,off,len);}
            };}
        };
        try(var pool=Executors.newSingleThreadExecutor()){
            Future<Integer> receiver=pool.submit(()->receiveStatus(claim,file));
            try{assertTrue(storage.reading.await(5,TimeUnit.SECONDS));clock.advance(Duration.ofMinutes(11));cleanup.run();assertEquals("succeeded",jdbc.queryForObject("SELECT state FROM file_cleanup_jobs",String.class));}
            finally{storage.resume.countDown();}
            assertEquals(failAfterWrite?503:409,receiver.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,storage.objects.size(),"The object arrived after the first idempotent S3 delete");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM file_cleanup_jobs WHERE state='pending'",Integer.class)>0,"A completed old cleanup cannot swallow a late write");
        cleanup.run();assertTrue(storage.objects.isEmpty());assertNotEquals("uploaded",jdbc.queryForObject("SELECT state FROM file_uploads",String.class));
    }
    private UploadClaim claim(){var intent=uploads.intent(actor,"avatar","test","image/png",3,null);return uploads.claim(Long.parseLong(intent.uploadId()),intent.uploadToken());}
    private int receiveStatus(UploadClaim claim,MockMultipartFile file){try{uploads.receive(claim,file);return 201;}catch(BusinessException e){return e.getStatus();}}
    static class MutableClock extends Clock {
        Instant instant=Instant.parse("2026-10-15T00:00:00Z");
        synchronized void advance(Duration duration){instant=instant.plus(duration);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public synchronized Instant instant(){return instant;}
    }
    static class LateStore implements ObjectStorage {
        final Map<String,byte[]> objects=new ConcurrentHashMap<>();final CountDownLatch reading=new CountDownLatch(1),resume=new CountDownLatch(1);
        int writes;boolean failAfterWrite;
        public void put(String key,InputStream input,String mime){try{byte[] bytes=input.readAllBytes();objects.put(key,bytes);writes++;if(failAfterWrite)throw FilePurpose.unavailable();}catch(IOException e){throw new UncheckedIOException(e);}}
        public InputStream open(String key){return new ByteArrayInputStream(objects.get(key));}
        public void delete(String key){objects.remove(key);}
        public String sign(String key,Duration ttl){throw new UnsupportedOperationException("Not used by upload lifecycle");}
    }
}
