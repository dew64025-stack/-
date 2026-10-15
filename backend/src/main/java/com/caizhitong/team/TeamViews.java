package com.caizhitong.team;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class TeamViews {
    private TeamViews() {}
    public record Team(String id, String title, String goal, String status, int version, String leaderId, Instant createdAt) {}
    public record Detail(Team team, List<JsonNode> openRoles, int memberCount, boolean isLeader, boolean isMember, boolean canApply) {}
    public record Page(List<Team> items, String nextCursor, boolean hasMore) {}
    public record Application(String id, String teamId, String message, String status, Instant createdAt, Instant updatedAt) {}
    public record ApplicationPage(List<Application> items, String nextCursor, boolean hasMore) {}
    public record Left(boolean left) {}
}
