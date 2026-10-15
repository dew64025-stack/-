package com.caizhitong.admin.identity;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.identity.*;
import com.caizhitong.security.*;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
@Service public class StudentRosterService {
    private final JdbcTemplate jdbc;private final CurrentActor current;private final SnowflakeIds ids;private final IdentityService identity;private final JsonMapper mapper;
    public StudentRosterService(JdbcTemplate jdbc,CurrentActor current,SnowflakeIds ids,IdentityService identity,JsonMapper mapper){this.jdbc=jdbc;this.current=current;this.ids=ids;this.identity=identity;this.mapper=mapper;}
    @BusinessWrite(permission="identity.import") public IdentityViews.Job register(long attachment){Actor actor=current.requirePermission("identity.import");long id=ids.nextId();jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,created_at,updated_at) VALUES(?,'student_roster',?,'pending',?,?,'admin',?,?)",id,mapper.writeValueAsString(Map.of("attachmentId",Long.toString(attachment))),actor.campusId(),actor.userId(),identity.now(),identity.now());identity.audit(actor,"identity.roster.import","async_job",id,null);return new IdentityViews.Job(Long.toString(id),"pending",null);}
}
