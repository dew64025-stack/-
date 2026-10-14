package com.caizhitong.security;

import java.util.Set;

/** Fixed server policy. Ownership and campus constraints still belong to each application service. */
public final class RoutePolicy {
    private RoutePolicy() {}
    private static final String BASE = "/api/v1";
    private static final Set<String> PUBLIC_GET = Set.of("/campuses", "/campus/regions", "/campus/organizations", "/home/overview", "/search",
            "/opportunities", "/campus-issues", "/campus-issues/map", "/crowd/observations", "/resources", "/mutual-aid/posts", "/teams");
    private static final Set<String> STUDENT_CREDENTIALS = Set.of("/auth/wechat/login", "/auth/token/refresh", "/auth/logout", "/auth/phone/bind");
    private static final Set<String> ADMIN_CREDENTIALS = Set.of("/admin/auth/login", "/admin/auth/mfa/verify", "/admin/auth/token/refresh", "/admin/auth/logout", "/admin/auth/reauth");
    public static boolean publicRequest(String method, String path) {
        String route = relative(path);
        if ("GET".equals(method)) return PUBLIC_GET.contains(route)
                || route.matches("/(opportunities|teams|resources|campus-issues)/[0-9]+")
                || route.matches("/mutual-aid/posts/[0-9]+(/ratings)?") || route.matches("/resources/[0-9]+/slots");
        return "POST".equals(method) && Set.of("/auth/wechat/login", "/auth/token/refresh", "/auth/logout").contains(route);
    }
    public static boolean binaryUpload(String method, String path) {
        return "POST".equals(method) && relative(path).matches("/files/uploads/[0-9]+");
    }
    public static boolean credentialException(String method, String path) {
        if (!"POST".equals(method)) return false;
        String route = relative(path);
        return STUDENT_CREDENTIALS.contains(route) || ADMIN_CREDENTIALS.contains(route)
                || Set.of("/materials/upload-intents", "/files/upload-intents", "/admin/files/upload-intents").contains(route)
                || route.matches("/(admin/)?files/[0-9]+/download-url")
                || route.matches("/admin/reservations/[0-9]+/check-in-token")
                || binaryUpload(method, path);
    }
    public static boolean adminCredential(String path) { return ADMIN_CREDENTIALS.contains(relative(path)); }
    public static boolean independentCredential(String method,String path) {
        return "POST".equals(method) && Set.of("/auth/wechat/login","/auth/token/refresh","/auth/logout",
                "/admin/auth/login","/admin/auth/mfa/verify","/admin/auth/token/refresh").contains(relative(path));
    }
    public static boolean ownRights(String method, String path) {
        String route = relative(path);
        return "GET".equals(method) && (Set.of("/me/penalties", "/me/appeals", "/me/data-requests").contains(route)
                || route.matches("/me/data-requests/[0-9]+"))
                || "POST".equals(method) && (Set.of("/appeals", "/me/data-exports", "/me/account-deletion").contains(route)
                || route.matches("/me/data-requests/[0-9]+/cancel"));
    }
    private static String relative(String path) { return path != null && path.startsWith(BASE + "/") ? path.substring(BASE.length()) : ""; }
}
