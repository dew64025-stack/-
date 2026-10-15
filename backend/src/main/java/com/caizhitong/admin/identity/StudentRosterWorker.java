package com.caizhitong.admin.identity;

import com.caizhitong.common.*;
import com.caizhitong.identity.*;
import com.caizhitong.security.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** One leased job per run; network/file reads and CSV parsing never hold a database transaction. */
@Component("studentRosterWorker") public class StudentRosterWorker implements Runnable {
    private record Claim(long id,long user,long campus,String attachment,String token,int attempt,int maxRetries){}
    private record Row(String number,String college,String grade,String status){}
    public record RowError(int row,String code){}
    public record Result(int successCount,int errorCount,List<RowError> errors){}
    private record Parsed(List<Row> rows,int invalid,List<RowError> errors){}
    private final JdbcTemplate jdbc;private final IdentityFiles files;private final ActorLoader actors;private final IdentityService identity;private final SensitiveDataCrypto crypto;private final SnowflakeIds ids;private final JsonMapper mapper;private final TransactionTemplate tx;
    public StudentRosterWorker(JdbcTemplate jdbc,IdentityFiles files,ActorLoader actors,IdentityService identity,SensitiveDataCrypto crypto,SnowflakeIds ids,JsonMapper mapper,PlatformTransactionManager manager){this.jdbc=jdbc;this.files=files;this.actors=actors;this.identity=identity;this.crypto=crypto;this.ids=ids;this.mapper=mapper;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(10);}
    @Override public void run(){Claim claim=tx.execute(status->claim());if(claim==null)return;try{Actor actor=authorized(claim);byte[] bytes=files.roster(actor,IdentityInputs.id(claim.attachment()),10*1024*1024);Parsed parsed=parse(bytes,claim.campus());tx.executeWithoutResult(status->finish(claim,parsed));}catch(RuntimeException e){tx.executeWithoutResult(status->fail(claim));}}
    private Claim claim(){LocalDateTime now=identity.now();jdbc.update("UPDATE async_jobs SET status=CASE WHEN retry_count>=max_retries THEN 'failed' ELSE 'pending' END,claim_token=NULL,next_retry_at=?,updated_at=?,error='IMPORT_LEASE_EXPIRED' WHERE job_type='student_roster' AND access_domain='admin' AND campus_id IS NOT NULL AND owner_user_id IS NOT NULL AND status='running' AND picked_at<?",now,now,now.minusMinutes(5));
        var rows=jdbc.query("SELECT id,owner_user_id,campus_id,payload_json,retry_count,max_retries FROM async_jobs WHERE job_type='student_roster' AND access_domain='admin' AND campus_id IS NOT NULL AND owner_user_id IS NOT NULL AND status='pending' AND retry_count<max_retries AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",(r,i)->new Claim(r.getLong(1),r.getLong(2),r.getLong(3),mapper.readTree(r.getString(4)).path("attachmentId").asString(),UUID.randomUUID().toString(),r.getInt(5)+1,r.getInt(6)),now);if(rows.isEmpty())return null;Claim claim=rows.getFirst();jdbc.update("UPDATE async_jobs SET status='running',picked_at=?,claim_token=?,retry_count=retry_count+1,updated_at=? WHERE id=? AND status='pending'",now,claim.token(),now,claim.id());return claim;}
    private Actor authorized(Claim claim){return actors.load(claim.user(),claim.campus(),SessionKind.ADMIN).filter(a->a.kind()==SessionKind.ADMIN&&"active".equals(a.status())&&a.permissions().contains("identity.import")).orElseThrow(IdentityService::forbidden);}
    private void finish(Claim claim,Parsed parsed){if(jdbc.queryForList("SELECT id FROM async_jobs WHERE id=? AND status='running' AND claim_token=? FOR UPDATE",Long.class,claim.id(),claim.token()).isEmpty())return;jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,claim.user());authorized(claim);
        for(Row row:parsed.rows())jdbc.update("INSERT INTO student_roster(id,campus_id,student_id_search,student_id_encrypted,college_encrypted,grade_encrypted,status,import_job_id,created_at) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE student_id_encrypted=VALUES(student_id_encrypted),college_encrypted=VALUES(college_encrypted),grade_encrypted=VALUES(grade_encrypted),status=VALUES(status),import_job_id=VALUES(import_job_id)",ids.nextId(),claim.campus(),identity.hash("student",row.number()),crypto.encrypt("student",row.number()),crypto.encrypt("roster-college",row.college()),crypto.encrypt("roster-grade",row.grade()),row.status(),claim.id(),identity.now());
        jdbc.update("UPDATE async_jobs SET status='succeeded',result_json=?,error=NULL,claim_token=NULL,updated_at=? WHERE id=? AND claim_token=?",mapper.writeValueAsString(new Result(parsed.rows().size(),parsed.invalid(),parsed.errors())),identity.now(),claim.id(),claim.token());}
    private void fail(Claim claim){jdbc.update("UPDATE async_jobs SET status=?,next_retry_at=?,claim_token=NULL,error='IMPORT_FAILED',updated_at=? WHERE id=? AND status='running' AND claim_token=?",claim.attempt()>=claim.maxRetries()?"failed":"pending",identity.now().plusSeconds(30),identity.now(),claim.id(),claim.token());}
    private Parsed parse(byte[] bytes,long campus){if(bytes==null||bytes.length>10*1024*1024)throw IdentityInputs.invalid();String csv;try{csv=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw IdentityInputs.invalid();}if(csv.startsWith("\uFEFF"))csv=csv.substring(1);var records=csv(csv);if(records.isEmpty()||!records.getFirst().equals(List.of("studentNumber","college","grade","status")))throw IdentityInputs.invalid();
        Set<String> colleges=Set.copyOf(jdbc.queryForList("SELECT name FROM campus_organizations WHERE campus_id=? AND type='college' AND status='active' AND deleted_at IS NULL",String.class,campus));Set<String> grades=new HashSet<>();var configs=jdbc.queryForList("SELECT config_value FROM campus_configs WHERE campus_id=? AND config_key='profile.allowedGrades'",String.class,campus);if(!configs.isEmpty()){var node=mapper.readTree(configs.getFirst());if(node.isString())node=mapper.readTree(node.asString());grades.addAll(IdentityInputs.strings(node));}
        List<Row> valid=new ArrayList<>();List<RowError> errors=new ArrayList<>();Set<String> seen=new HashSet<>();int invalid=0;
        for(int i=1;i<records.size();i++){var fields=records.get(i);String error=null;if(fields.size()!=4)error="INVALID_COLUMNS";else if(fields.get(0).isBlank()||fields.get(0).length()>32||fields.get(1).length()>128||fields.get(2).length()>32||!colleges.contains(fields.get(1))||!grades.contains(fields.get(2))||!Set.of("active","inactive").contains(fields.get(3)))error="INVALID_VALUE";else if(!seen.add(fields.get(0)))error="DUPLICATE_STUDENT";
            if(error!=null){invalid++;if(errors.size()<20)errors.add(new RowError(i+1,error));}else valid.add(new Row(fields.get(0),fields.get(1),fields.get(2),fields.get(3)));}
        return new Parsed(valid,invalid,errors);}
    private List<List<String>> csv(String text){List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(c=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;closed=true;}}else field.append(c);continue;}
            if(c=='"'){if(field.length()!=0||closed)throw IdentityInputs.invalid();quoted=true;}
            else if(c==','||c=='\n'||c=='\r'){row.add(field.toString());field.setLength(0);closed=false;if(c!=','){if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;rows.add(List.copyOf(row));row.clear();}}
            else{if(closed)throw IdentityInputs.invalid();field.append(c);}}
        if(quoted)throw IdentityInputs.invalid();if(field.length()>0||!row.isEmpty()||closed){row.add(field.toString());rows.add(List.copyOf(row));}return rows;}
}
