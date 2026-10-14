package com.caizhitong.security;

import com.caizhitong.common.BusinessException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;

class CurrentActorTest {
    Actor actor;
    CurrentActor current = new CurrentActor((u, c, k) -> Optional.ofNullable(actor));
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void login(Actor value) { actor = value; SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(value, null, List.of())); }
    @Test void missingLoginIs401() { assertEquals(401, assertThrows(BusinessException.class, current::require).getStatus()); }
    @Test void studentRequiresBothVerifiedPhoneAndVerifiedIdentity() {
        login(new Actor(1, 1, Set.of(), false, true, "active", SessionKind.STUDENT));
        assertEquals(403, assertThrows(BusinessException.class, current::requireStudent).getStatus());
        login(new Actor(1, 1, Set.of(), true, false, "active", SessionKind.STUDENT));
        assertEquals(403, assertThrows(BusinessException.class, current::requireStudent).getStatus());
        login(new Actor(1, 1, Set.of(), true, true, "active", SessionKind.ADMIN));
        assertEquals(403, assertThrows(BusinessException.class, current::requireStudent).getStatus());
    }
    @Test void permissionAndStatusAreReloadedFromDatabase() {
        login(new Actor(1, 1, Set.of("content.review"), true, true, "active", SessionKind.ADMIN));
        current.requirePermission("content.review");
        actor = new Actor(1, 1, Set.of(), true, true, "active", SessionKind.ADMIN);
        assertEquals(403, assertThrows(BusinessException.class, () -> current.requirePermission("content.review")).getStatus());
        actor = new Actor(1, 1, Set.of(), true, true, "disabled", SessionKind.ADMIN);
        assertEquals(403, assertThrows(BusinessException.class, current::require).getStatus());
    }
    @Test void bannedIdentityRetainsOnlyExplicitOwnRightsAndDeletedIdentityIsRejected() {
        login(new Actor(1, 1, Set.of(), true, true, "banned", SessionKind.STUDENT));
        assertEquals("banned", current.requireAuthenticatedIdentity().status());
        assertEquals(403, assertThrows(BusinessException.class, current::require).getStatus());
        actor = null;
        assertEquals(401, assertThrows(BusinessException.class, current::requireAuthenticatedIdentity).getStatus());
    }
    @Test void unrelatedAdminRolesCannotRevealAnonymousIdentity() {
        assertFalse(RolePermissions.permissions(Set.of("campus_admin", "platform_admin")).contains("security.investigate"));
        assertEquals(Set.of("security.investigate", "audit.read"), RolePermissions.permissions(Set.of("security_investigator")));
        assertTrue(RolePermissions.permissions(Set.of("student", "user", "guest")).isEmpty());
    }
}
