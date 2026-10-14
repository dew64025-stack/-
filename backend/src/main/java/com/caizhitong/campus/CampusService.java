package com.caizhitong.campus;
import com.caizhitong.common.BusinessException;
import com.caizhitong.security.CurrentActor;
import com.caizhitong.identity.IdentityInputs;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service public class CampusService {
    public record Campus(String id,String name,String code){}
    public record Region(String id,String name,String type,String parentId){}
    public record Organization(String id,String name,String type){}
    private final JdbcTemplate jdbc;private final CurrentActor current;
    public CampusService(JdbcTemplate jdbc,CurrentActor current){this.jdbc=jdbc;this.current=current;}
    public List<Campus> campuses(){return jdbc.query("SELECT id,name,code FROM campuses WHERE status='active' AND deleted_at IS NULL ORDER BY id",(r,i)->new Campus(r.getString(1),r.getString(2),r.getString(3)));}
    public long open(String code){var rows=jdbc.queryForList("SELECT id FROM campuses WHERE code=? AND status='active' AND deleted_at IS NULL",Long.class,code);if(rows.isEmpty())throw new BusinessException(400,"CAMPUS_UNAVAILABLE","校园未开放");return rows.getFirst();}
    private long scope(String code){return current.optional().map(actor->actor.campusId()).orElseGet(()->{if(code==null)throw IdentityInputs.invalid();return open(code);});}
    public List<Region> regions(String code){return jdbc.query("SELECT id,name,type,parent_id FROM campus_regions WHERE campus_id=? AND status='active' AND deleted_at IS NULL ORDER BY id",(r,i)->new Region(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),scope(code));}
    public List<Organization> organizations(String code,String type){if(type!=null&&!Set.of("college","department","club").contains(type))throw IdentityInputs.invalid();return jdbc.query("SELECT id,name,type FROM campus_organizations WHERE campus_id=? AND status='active' AND deleted_at IS NULL AND (? IS NULL OR type=?) ORDER BY id",(r,i)->new Organization(r.getString(1),r.getString(2),r.getString(3)),scope(code),type,type);}
}
