package com.caizhitong.community;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.security.Actor;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The business account executes these narrow routines and never reads the identity mapping. */
@Component
public class RoutineAnonymousOwnership implements AnonymousOwnership {
    private final JdbcTemplate jdbc;
    private final SensitiveDataCrypto crypto;
    public RoutineAnonymousOwnership(JdbcTemplate jdbc,SensitiveDataCrypto crypto){this.jdbc=jdbc;this.crypto=crypto;}
    public boolean owns(Actor actor,String targetType,long targetId){
        return Boolean.TRUE.equals(jdbc.queryForObject("CALL community_owner_check(?,?,?,?)",Boolean.class,actor.campusId(),targetType,targetId,proof(actor)));
    }
    public List<Long> posts(Actor actor,String status,long before,int limit){
        return jdbc.queryForList("CALL community_owner_posts(?,?,?,?,?)",Long.class,actor.campusId(),proof(actor),status,before,limit);
    }
    private String proof(Actor actor){return HexFormat.of().formatHex(crypto.searchHash("anonymous-identity",Long.toString(actor.userId())));}
}
