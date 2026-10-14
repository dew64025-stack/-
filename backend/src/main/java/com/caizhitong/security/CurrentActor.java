package com.caizhitong.security;
import java.util.Optional;
import com.caizhitong.common.BusinessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
@Component
public class CurrentActor {
    private final ActorLoader loader;
    public CurrentActor(ActorLoader loader) { this.loader = loader; }
    public Optional<Actor> optional() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Actor actor)) return Optional.empty();
        return loader.load(actor.userId(), actor.campusId(), actor.kind())
                .filter(current -> current.kind() == actor.kind() && current.userId() == actor.userId()
                        && current.campusId() == actor.campusId());
    }
    public Actor requireAuthenticatedIdentity() { return optional().orElseThrow(() -> new BusinessException(401, "UNAUTHENTICATED", "请先登录")); }
    public Actor require() {
        Actor actor = requireAuthenticatedIdentity();
        if (!"active".equals(actor.status())) throw forbidden();
        return actor;
    }
    public Actor requireStudent() {
        Actor actor = require();
        if (actor.kind() != SessionKind.STUDENT || !actor.phoneBound() || !actor.studentVerified()) throw forbidden();
        return actor;
    }
    public Actor requirePermission(String permission) {
        Actor actor = require();
        if (actor.kind() != SessionKind.ADMIN || !actor.permissions().contains(permission)) throw forbidden();
        return actor;
    }
    private BusinessException forbidden() { return new BusinessException(403, "FORBIDDEN", "无权执行此操作"); }
}
