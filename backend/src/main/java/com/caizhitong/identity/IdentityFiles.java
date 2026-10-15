package com.caizhitong.identity;

import com.caizhitong.security.Actor;
/** Task4 must validate owner, campus, private purpose, clean scan and bounded bytes. No URL input. */
public interface IdentityFiles {
    void validateAvatar(Actor actor,long attachmentId);
    void validateRoster(Actor actor,long attachmentId);
    byte[] roster(Actor actor,long attachmentId,int maxBytes);
}
