package com.caizhitong.discovery;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.pagination.CursorCodec;
import com.caizhitong.common.pagination.CursorPayload;
import com.caizhitong.common.pagination.PageLimits;
import com.caizhitong.common.search.SearchItem;
import com.caizhitong.issue.IssueReadBridge;
import com.caizhitong.mutualaid.MutualAidReadBridge;
import com.caizhitong.opportunity.OpportunityReadBridge;
import com.caizhitong.resource.ResourceReadBridge;
import com.caizhitong.team.TeamReadBridge;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    /** Stable cross-type order: type ascending, id descending. Anonymous community is absent by design. */
    private static final List<String> ALL_TYPES = List.of("issue", "mutual_aid", "opportunity", "resource", "team");
    private static final Comparator<SearchItem> ORDER = Comparator.comparing(SearchItem::type)
            .thenComparing((left, right) -> Long.compare(Long.parseLong(right.id()), Long.parseLong(left.id())));

    private final DiscoveryScope scopes;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final OpportunityReadBridge opportunities;
    private final IssueReadBridge issues;
    private final ResourceReadBridge resources;
    private final MutualAidReadBridge mutualAid;
    private final TeamReadBridge teams;

    public SearchService(DiscoveryScope scopes, CursorCodec cursors, SensitiveDataCrypto crypto,
                         OpportunityReadBridge opportunities, IssueReadBridge issues, ResourceReadBridge resources,
                         MutualAidReadBridge mutualAid, TeamReadBridge teams) {
        this.scopes = scopes;
        this.cursors = cursors;
        this.crypto = crypto;
        this.opportunities = opportunities;
        this.issues = issues;
        this.resources = resources;
        this.mutualAid = mutualAid;
        this.teams = teams;
    }

    public DiscoveryViews.SearchPage search(String campusCode, String q, List<String> types, String cursor, Integer limit) {
        String query = q == null ? "" : q.trim();
        if (query.isEmpty() || query.length() > 100) throw invalid();
        int size = PageLimits.resolve(limit);
        long campus = scopes.campus(campusCode);
        List<String> selected = selectTypes(types);
        String scope = "search:" + campus + ":"
                + HexFormat.of().formatHex(crypto.searchHash("search-scope",
                query + "|" + String.join(",", selected)));

        String cursorType = null;
        long cursorId = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload payload = cursors.decode(cursor, scope);
            if (!payload.sortKeys().keySet().equals(Set.of("type", "id"))) throw invalid();
            cursorType = payload.sortKeys().get("type");
            if (!ALL_TYPES.contains(cursorType)) throw invalid();
            try {
                cursorId = Long.parseLong(payload.sortKeys().get("id"));
            } catch (NumberFormatException error) {
                throw invalid();
            }
            if (cursorId <= 0) throw invalid();
        }

        List<SearchItem> merged = new ArrayList<>();
        for (String type : selected) {
            if (cursorType != null && type.compareTo(cursorType) < 0) continue;
            long before = type.equals(cursorType) ? cursorId : Long.MAX_VALUE;
            merged.addAll(fetch(type, campus, query, before, size + 1));
        }
        merged.sort(ORDER);
        boolean hasMore = merged.size() > size;
        List<SearchItem> page = List.copyOf(merged.subList(0, Math.min(size, merged.size())));
        String next = hasMore && !page.isEmpty()
                ? cursors.encode(new CursorPayload(scope, Map.of("type", page.getLast().type(), "id", page.getLast().id())))
                : null;
        return new DiscoveryViews.SearchPage(page, next, hasMore);
    }

    private List<SearchItem> fetch(String type, long campus, String query, long before, int limit) {
        return switch (type) {
            case "opportunity" -> opportunities.search(campus, query, before, limit);
            case "issue" -> issues.search(campus, query, before, limit);
            case "resource" -> resources.search(campus, query, before, limit);
            case "mutual_aid" -> mutualAid.search(campus, query, before, limit);
            case "team" -> teams.search(campus, query, before, limit);
            default -> List.of();
        };
    }

    private List<String> selectTypes(List<String> requested) {
        if (requested == null || requested.isEmpty()) return ALL_TYPES;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : requested) {
            if (value == null || value.isBlank()) continue;
            String type = value.trim();
            if (!ALL_TYPES.contains(type)) throw invalid();
            values.add(type);
        }
        if (values.isEmpty() || values.size() > ALL_TYPES.size()) throw invalid();
        return ALL_TYPES.stream().filter(values::contains).toList();
    }

    private BusinessException invalid() {
        return new BusinessException(400, "VALIDATION_FAILED", "请检查填写的信息");
    }
}
