package com.caizhitong.team;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class TeamViews {
    private TeamViews() {}
    public record Person(String id,String nickname) {}
    public record Member(String id,String userId,String role,List<String> skillTags,String status,String openRoleId) {}
    public record Team(String id,String opportunityId,String title,String goal,String status,int version,Person leader,List<Member> members,Instant createdAt) {}
    public record Detail(@com.fasterxml.jackson.annotation.JsonUnwrapped Team team, List<JsonNode> openRoles, int memberCount, boolean isLeader, boolean isMember, boolean canApply) {}
    public record Page(List<Team> items, String nextCursor, boolean hasMore) {}
    public record Application(String id, String teamId,Person applicant,String openRoleId, String message, String status, Instant createdAt, Instant updatedAt) {}
    public record ApplicationPage(List<Application> items, String nextCursor, boolean hasMore) {}
    public record Left(boolean left) {}
}
