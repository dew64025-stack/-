package com.caizhitong.security;

import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DatabaseActorLoader implements ActorLoader {
    private final EntityManager entityManager;
    public DatabaseActorLoader(EntityManager entityManager) { this.entityManager = entityManager; }
    @Override @Transactional(readOnly = true)
    public Optional<Actor> load(long userId, long campusId, SessionKind kind) {
        List<?> users = entityManager.createNativeQuery("""
                SELECT u.status,
                    CASE WHEN EXISTS (SELECT 1 FROM user_phones p WHERE p.user_id=u.id AND p.verified=1)
                        AND EXISTS (SELECT 1 FROM authorizations a WHERE a.user_id=u.id AND a.type='phone'
                            AND a.status='granted' AND a.revoked_at IS NULL
                            AND a.id=(SELECT latest.id FROM authorizations latest WHERE latest.user_id=u.id AND latest.type='phone'
                                ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1)) THEN 1 ELSE 0 END,
                    CASE WHEN EXISTS (SELECT 1 FROM student_verifications v WHERE v.user_id=u.id AND v.campus_id=u.campus_id AND v.status='verified') THEN 1 ELSE 0 END
                FROM users u JOIN campuses c ON c.id=u.campus_id
                WHERE u.id=:user AND u.campus_id=:campus AND u.deleted_at IS NULL AND u.status<>'deleted'
                    AND c.deleted_at IS NULL AND c.status='active'
                """).setParameter("user", userId).setParameter("campus", campusId).getResultList();
        if (users.isEmpty()) return Optional.empty();
        Object[] user = (Object[]) users.getFirst();
        List<?> codes = entityManager.createNativeQuery("""
                SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                WHERE ur.user_id=:user AND (ur.campus_id=:campus OR (ur.campus_id=0 AND r.code IN ('platform_admin','security_investigator')))
                """).setParameter("user", userId).setParameter("campus", campusId).getResultList();
        Set<String> roles = new HashSet<>(); codes.forEach(code -> roles.add(code.toString()));
        if (kind == SessionKind.ADMIN && roles.stream().noneMatch(RolePermissions::isAdminRole)) return Optional.empty();
        return Optional.of(new Actor(userId, campusId, kind == SessionKind.ADMIN ? RolePermissions.permissions(roles) : Set.of(),
                ((Number) user[1]).intValue() == 1, ((Number) user[2]).intValue() == 1, user[0].toString(), kind));
    }
}
