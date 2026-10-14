package com.caizhitong.security;
import java.util.Optional;
public interface ActorLoader { Optional<Actor> load(long userId, long campusId, SessionKind kind); }
