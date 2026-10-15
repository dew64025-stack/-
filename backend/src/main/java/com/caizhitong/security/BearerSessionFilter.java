package com.caizhitong.security;

import com.caizhitong.common.BusinessException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class BearerSessionFilter extends OncePerRequestFilter {
    private final SessionTokenService sessions; private final SessionKind[] kinds; private final FilterErrors errors;
    public BearerSessionFilter(SessionTokenService sessions, SessionKind kind, FilterErrors errors) { this(sessions, new SessionKind[]{kind}, errors); }
    /** Task 5（负责人A）：/jobs 收据同一路径需要同时接受学生与管理员会话，按声明顺序尝试匹配。 */
    public BearerSessionFilter(SessionTokenService sessions, SessionKind[] kinds, FilterErrors errors) { this.sessions = sessions; this.kinds = kinds; this.errors = errors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return RoutePolicy.independentCredential(request.getMethod(),request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer", 0, 6)) {
            try {
                if (Collections.list(request.getHeaders("Authorization")).size() != 1 || !authorization.matches("(?i)Bearer [A-Za-z0-9_-]{43}")) throw unauthenticated();
                Actor actor = null;
                for (SessionKind candidate : kinds) {
                    var found = sessions.authenticate(authorization.substring(7), candidate);
                    if (found.isPresent()) { actor = found.get(); break; }
                }
                if (actor == null) throw unauthenticated();
                var authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                        actor.permissions().stream().map(SimpleGrantedAuthority::new).toList());
                var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication); SecurityContextHolder.setContext(context);
            } catch (BusinessException error) { errors.write(request, response, error); return; }
            catch (DataAccessException error) { errors.write(request, response, new BusinessException(503, "IDENTITY_UNAVAILABLE", "身份服务暂时不可用", null, true)); return; }
        }
        chain.doFilter(request, response);
    }
    private static BusinessException unauthenticated() { return new BusinessException(401, "UNAUTHENTICATED", "会话无效，请重新登录"); }
}
