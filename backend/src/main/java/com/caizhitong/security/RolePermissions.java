package com.caizhitong.security;
import java.util.Set;
import java.util.Map;
import java.util.HashSet;
public final class RolePermissions {
    private RolePermissions() {}
    private static final Set<String> CAMPUS = Set.of("content.review", "report.review", "appeal.review", "workorder.read", "workorder.process",
            "workorder.assign", "issue.read", "issue.process", "issue.review", "resource.read", "resource.write", "resource.process",
            "identity.review", "identity.import", "file.upload", "file.read", "user.read", "user.roles", "campus.config.read",
            "campus.config.write", "statistics.read", "notice.read", "notice.write", "notice.parse", "notice.review",
            "opportunity.read", "opportunity.write", "opportunity.publish", "user.penalty", "credit.review", "audit.read",
            "integration.read", "integration.write", "integration.sync");
    private static final Map<String, Set<String>> MATRIX = Map.of(
            "content_moderator", Set.of("content.review", "report.review", "appeal.review"),
            "business_operator", Set.of("workorder.read", "workorder.process", "issue.read", "issue.process"),
            "resource_admin", Set.of("resource.read", "resource.write", "resource.process"),
            "campus_admin", CAMPUS,
            "platform_admin", Set.of("platform.campus.manage", "audit.read"),
            "security_investigator", Set.of("security.investigate", "audit.read"));
    public static Set<String> permissions(Set<String> roles) {
        Set<String> result = new HashSet<>(); roles.forEach(role -> result.addAll(MATRIX.getOrDefault(role, Set.of()))); return Set.copyOf(result);
    }
    public static boolean isAdminRole(String role) { return MATRIX.containsKey(role); }
}
