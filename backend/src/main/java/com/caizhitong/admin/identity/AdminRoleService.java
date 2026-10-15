package com.caizhitong.admin.identity;
import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.identity.*;
import com.caizhitong.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.caizhitong.identity.IdentityInputs.*;
@Service public class AdminRoleService {
    private final JdbcTemplate jdbc;private final CurrentActor current;private final AdminAuthService auth;private final IdentityService identity;private final SnowflakeIds ids;
    public AdminRoleService(JdbcTemplate jdbc,CurrentActor current,AdminAuthService auth,IdentityService identity,SnowflakeIds ids){this.jdbc=jdbc;this.current=current;this.auth=auth;this.identity=identity;this.ids=ids;}
    /** Called before the idempotency proxy, and repeated under target lock for a new write. */
    public Actor authorize(long user,long campus,String role){Actor actor=current.requirePermission("user.roles");if(campus!=actor.campusId()||!actor.permissions().containsAll(RolePermissions.permissions(Set.of(role))))throw IdentityService.forbidden();
        var target=jdbc.queryForList("SELECT id FROM users WHERE id=? AND campus_id=? AND deleted_at IS NULL",Long.class,user,campus);if(target.isEmpty())throw new BusinessException(404,"NOT_FOUND","未找到用户");if(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE code=?",Integer.class,role)==0)throw invalid();return actor;}
    @BusinessWrite(permission="user.roles") public IdentityViews.Changed grant(long user,Role input){String role=text(input.roleCode(),64,false);long campus=id(input.campusId());Actor actor=authorize(user,campus,role);lock(user);authorize(user,campus,role);auth.consumeReauth(text(input.reauthToken(),128,false),actor);long roleId=jdbc.queryForObject("SELECT id FROM roles WHERE code=?",Long.class,role);
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id,created_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",ids.nextId(),user,roleId,campus,identity.now());identity.audit(actor,"user.role.assign","user",user,text(input.reason(),512,false));return new IdentityViews.Changed(true,false);}
    @BusinessWrite(permission="user.roles") public IdentityViews.Changed revoke(long user,long campus,String role,String token,Revoke input){Actor actor=authorize(user,campus,role);lock(user);authorize(user,campus,role);auth.consumeReauth(token,actor);jdbc.update("DELETE FROM user_roles WHERE user_id=? AND campus_id=? AND role_id=(SELECT id FROM roles WHERE code=?)",user,campus,role);identity.audit(actor,"user.role.revoke","user",user,text(input.reason(),512,false));return new IdentityViews.Changed(false,true);}
    private void lock(long user){jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);}
}
