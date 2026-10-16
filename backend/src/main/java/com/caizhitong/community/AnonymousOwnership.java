package com.caizhitong.community;

import com.caizhitong.security.Actor;
import java.util.List;

public interface AnonymousOwnership {
    boolean owns(Actor actor,String targetType,long targetId);
    List<Long> posts(Actor actor,String status,long before,int limit);
}
