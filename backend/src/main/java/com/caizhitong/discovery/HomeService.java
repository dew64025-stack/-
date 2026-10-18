package com.caizhitong.discovery;

import com.caizhitong.campus.CampusReadBridge;
import com.caizhitong.common.BusinessException;
import com.caizhitong.common.search.HomeCard;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.issue.IssueReadBridge;
import com.caizhitong.messaging.MessagingService;
import com.caizhitong.mutualaid.MutualAidReadBridge;
import com.caizhitong.opportunity.OpportunityReadBridge;
import com.caizhitong.opportunity.ReminderReadBridge;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HomeService {
    private static final int SECTION = 5;
    private final CurrentActor current;
    private final DiscoveryScope scopes;
    private final CampusReadBridge campusRead;
    private final OpportunityReadBridge opportunities;
    private final ReminderReadBridge reminders;
    private final IssueReadBridge issues;
    private final MutualAidReadBridge mutualAid;
    private final MessagingService messaging;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HomeService(CurrentActor current, DiscoveryScope scopes, CampusReadBridge campusRead,
                       OpportunityReadBridge opportunities, ReminderReadBridge reminders, IssueReadBridge issues,
                       MutualAidReadBridge mutualAid, MessagingService messaging, JdbcTemplate jdbc, Clock clock) {
        this.current = current;
        this.scopes = scopes;
        this.campusRead = campusRead;
        this.opportunities = opportunities;
        this.reminders = reminders;
        this.issues = issues;
        this.mutualAid = mutualAid;
        this.messaging = messaging;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public DiscoveryViews.Overview overview(String campusCode, String regionId, Double latitude, Double longitude,
                                            Integer radiusMeters) {
        long campus = scopes.campus(campusCode);
        Long region = regionId == null || regionId.isBlank() ? null : IdentityInputs.id(regionId);
        if (region != null && !campusRead.regionExists(campus, region)) {
            throw new BusinessException(400, "VALIDATION_FAILED", "请检查填写的信息");
        }
        validateLocation(latitude, longitude, radiusMeters);

        var actor = current.optional();
        List<HomeCard> deadlines = List.of();
        List<HomeCard> recommendations = List.of();
        boolean nearbyEnabled = actor.isEmpty() || setting(actor.get().userId(), "nearby_enabled");
        int unread = 0;
        if (actor.isPresent()) {
            deadlines = reminders.upcoming(actor.get().userId(), clock.instant(), SECTION);
            if (setting(actor.get().userId(), "personalization_enabled")) {
                Set<String> shown = deadlines.stream().map(HomeCard::id).collect(java.util.stream.Collectors.toSet());
                recommendations = opportunities.recommendations(campus, SECTION + shown.size()).stream()
                        .filter(card -> !shown.contains(card.id())).limit(SECTION).toList();
            }
            unread = messaging.unreadTotal(actor.get().userId(), campus);
        }
        List<HomeCard> hot = issues.hot(campus, SECTION);
        String nearbyMode;
        List<HomeCard> nearby;
        if (region != null && nearbyEnabled) {
            nearbyMode = "region";
            nearby = mutualAid.nearby(campus, region, SECTION);
        } else {
            // No structured coordinates exist for mutual-aid posts; never fabricate distance.
            nearbyMode = "unavailable";
            nearby = List.of();
        }
        return new DiscoveryViews.Overview(deadlines, recommendations, hot, nearby, nearbyMode, unread);
    }

    /** Privacy settings default to off; no row means no consent. */
    private boolean setting(long userId, String column) {
        var rows = jdbc.queryForList("SELECT " + column + " FROM privacy_settings WHERE user_id=?", userId);
        if (rows.isEmpty()) return false;
        Object value = rows.getFirst().get(column);
        return value instanceof Boolean bool ? bool : value instanceof Number number && number.intValue() != 0;
    }

    private void validateLocation(Double latitude, Double longitude, Integer radiusMeters) {
        if (latitude == null && longitude == null) {
            if (radiusMeters != null) throw new BusinessException(400, "VALIDATION_FAILED", "请检查填写的信息");
            return;
        }
        if (latitude == null || longitude == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new BusinessException(400, "VALIDATION_FAILED", "请检查填写的信息");
        }
        if (radiusMeters != null && (radiusMeters < 100 || radiusMeters > 3000)) {
            throw new BusinessException(400, "VALIDATION_FAILED", "请检查填写的信息");
        }
    }
}
