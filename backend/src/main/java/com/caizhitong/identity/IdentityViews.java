package com.caizhitong.identity;
import java.util.*;
import java.time.Instant;
public final class IdentityViews {
    private IdentityViews() {}
    public record User(String id,String campusId,String nickname,String avatarUrl,String status,boolean phoneBound,String phoneMasked,String verificationStatus,int version) {}
    public record Profile(String college,String grade,List<String> skills,List<String> interests,int version) {}
    public record Verification(String id,String status,String studentNumberMasked,String rejectReason,Instant createdAt,int version,boolean rosterMatched) {}
    public record Bound(boolean phoneBound,String phoneMasked) {}
    public record Changed(boolean assigned,boolean revoked) {}
    public record Revoked(boolean revoked) {}
    public record Page<T>(List<T> items,String nextCursor,boolean hasMore) {}
    public record Job(String id,String status,Object result) {}
}
