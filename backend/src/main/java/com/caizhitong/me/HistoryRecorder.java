package com.caizhitong.me;

import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import com.caizhitong.security.SessionKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Records opted-in detail views for public, non-anonymous content. History is best-effort:
 * a failure here never changes the read response. Anonymous community routes are not matched.
 */
@Component
public class HistoryRecorder implements HandlerInterceptor {
    private static final Pattern SIMPLE = Pattern.compile("^/api/v1/(opportunities|resources|campus-issues|teams)/([0-9]{1,19})$");
    private static final Pattern MUTUAL_AID = Pattern.compile("^/api/v1/mutual-aid/posts/([0-9]{1,19})$");

    private final CurrentActor current;
    private final MeService me;

    public HistoryRecorder(CurrentActor current, MeService me) {
        this.current = current;
        this.me = me;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex != null || !"GET".equals(request.getMethod())) return;
        if (response.getStatus() < 200 || response.getStatus() >= 300) return;
        String path = request.getRequestURI();
        String type;
        String value;
        Matcher simple = SIMPLE.matcher(path);
        Matcher mutual = MUTUAL_AID.matcher(path);
        if (simple.matches()) {
            type = switch (simple.group(1)) {
                case "opportunities" -> "opportunity";
                case "resources" -> "resource";
                case "campus-issues" -> "issue";
                default -> "team";
            };
            value = simple.group(2);
        } else if (mutual.matches()) {
            type = "mutual_aid";
            value = mutual.group(1);
        } else {
            return;
        }
        Actor actor = current.optional().orElse(null);
        if (actor == null || actor.kind() != SessionKind.STUDENT) return;
        try {
            me.recordView(actor, type, IdentityInputs.id(value));
        } catch (RuntimeException ignored) {
            // Browsing history must never break the underlying read.
        }
    }
}
