package com.caizhitong.material.files;
import com.caizhitong.security.Actor;
/** Hydrate only outside the business write proxy; never store the resulting temporary URL. */
public interface AvatarUrls { String avatarUrl(Actor actor,long attachmentId); }
