package com.caizhitong.community;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.security.Actor;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** H2-only substitute for the MySQL definer procedures; never included in production. */
@TestConfiguration
public class H2AnonymousOwnership {
    @Bean @Primary AnonymousOwnership testOwnership(JdbcTemplate jdbc,SensitiveDataCrypto crypto){
        return new AnonymousOwnership(){
            private String proof(Actor actor){return HexFormat.of().formatHex(crypto.searchHash("anonymous-identity",Long.toString(actor.userId())));}
            public boolean owns(Actor actor,String type,long id){
                if(type.equals("community_post"))return jdbc.queryForObject("SELECT COUNT(*) FROM pseudo_identities i JOIN community_posts p ON p.id=i.post_id WHERE p.campus_id=? AND p.id=? AND i.user_id_search=?",Integer.class,actor.campusId(),id,proof(actor))>0;
                if(type.equals("community_comment"))return jdbc.queryForObject("SELECT COUNT(*) FROM pseudo_identities i JOIN community_comments c ON c.id=i.comment_id JOIN community_posts p ON p.id=c.post_id WHERE p.campus_id=? AND c.id=? AND i.user_id_search=?",Integer.class,actor.campusId(),id,proof(actor))>0;
                return false;
            }
            public List<Long> posts(Actor actor,String status,long before,int limit){
                return jdbc.queryForList("SELECT p.id FROM community_posts p WHERE p.campus_id=? AND p.id<? AND (? IS NULL OR p.status=?) AND EXISTS(SELECT 1 FROM pseudo_identities i WHERE i.post_id=p.id AND i.user_id_search=?) ORDER BY p.id DESC LIMIT ?",Long.class,actor.campusId(),before,status,status,proof(actor),limit);
            }
        };
    }
}
