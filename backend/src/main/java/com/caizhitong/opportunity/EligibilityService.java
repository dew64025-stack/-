package com.caizhitong.opportunity;

import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;

@Service
public class EligibilityService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final OpportunityService opportunities;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    public EligibilityService(JdbcTemplate jdbc, CurrentActor current, OpportunityService opportunities, SnowflakeIds ids,
                              Clock clock, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.current = current;
        this.opportunities = opportunities;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
    }

    /** 真正异步 202：入队时保存服务器读取的当前画像 + profileVersion + 规则版本（快照随 worker 读取）。 */
    @BusinessWrite(student = true)
    public OpportunityViews.AsyncJob enqueue(String opportunityId) {
        long opportunity = opportunities.visible(opportunityId);
        Actor actor = current.requireStudent();
        var profile = profile(actor.userId());
        Integer rulesVersion = jdbc.queryForObject("SELECT rules_version FROM opportunities WHERE id=?", Integer.class, opportunity);
        long job = ids.nextId();
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> payload = Map.of(
                "opportunityId", Long.toString(opportunity),
                "rulesVersion", rulesVersion == null ? 0 : rulesVersion,
                "profileVersion", profile.version() == null ? 0 : profile.version(),
                "profile", Map.of("college", profile.college() == null ? "" : profile.college(),
                        "grade", profile.grade() == null ? "" : profile.grade(),
                        "skills", profile.skills(), "interests", profile.interests()));
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,retry_count,max_retries,created_at,updated_at) VALUES(?,'eligibility_check',?,'pending',?,?,'student',0,3,?,?)",
                job, mapper.writeValueAsString(payload), actor.campusId(), actor.userId(), now, now);
        return new OpportunityViews.AsyncJob(Long.toString(job), "eligibility_check", "pending", null, null, clock.instant(), clock.instant());
    }

    /** 本人最新匹配结果；画像快照属于私密数据，不返回。 */
    public OpportunityViews.EligibilityResult latest(String opportunityId) {
        long opportunity = id(opportunityId);
        Actor actor = current.requireStudent();
        var rows = jdbc.query("SELECT id,conclusion,reason_json,rules_version,profile_version,created_at FROM opportunity_eligibility_checks WHERE opportunity_id=? AND user_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                (r, i) -> new OpportunityViews.EligibilityResult(r.getString("id"), opportunityId, r.getString("conclusion"),
                        reasons(r.getString("reason_json")), r.getObject("rules_version", Integer.class), r.getObject("profile_version", Integer.class),
                        r.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), opportunity, actor.userId());
        if (rows.isEmpty()) throw new com.caizhitong.common.BusinessException(404, "NOT_FOUND", "暂无匹配结果");
        return rows.getFirst();
    }

    public record ProfileRow(String college, String grade, List<String> skills, List<String> interests, Integer version) {}

    private ProfileRow profile(long userId) {
        var rows = jdbc.query("SELECT college,grade,skills,interests,version FROM user_profiles WHERE user_id=?",
                (r, i) -> new ProfileRow(r.getString("college"), r.getString("grade"), strings(r.getString("skills")),
                        strings(r.getString("interests")), r.getInt("version")), userId);
        if (rows.isEmpty()) return new ProfileRow(null, null, List.of(), List.of(), null);
        return rows.getFirst();
    }

    private List<String> strings(String json) {
        if (json == null) return List.of();
        JsonNode node = mapper.readTree(json);
        if (node.isString()) node = mapper.readTree(node.asString());
        if (!node.isArray()) return List.of();
        List<String> values = new java.util.ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    private List<EligibilityEngine.Reason> reasons(String json) {
        if (json == null) return List.of();
        JsonNode node = mapper.readTree(json);
        if (!node.isArray()) return List.of();
        List<EligibilityEngine.Reason> reasons = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            reasons.add(new EligibilityEngine.Reason(
                    item.path("field").asString(""), item.path("expected").asString(""),
                    item.path("actual").isTextual() ? item.path("actual").asString() : null,
                    item.path("result").asString(""), item.path("message").asString("")));
        }
        return List.copyOf(reasons);
    }
}
