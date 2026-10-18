package com.caizhitong.discovery;

import com.caizhitong.common.search.HomeCard;
import com.caizhitong.common.search.SearchItem;
import java.util.List;

public final class DiscoveryViews {
    private DiscoveryViews() {}

    public record Overview(List<HomeCard> deadlines, List<HomeCard> recommendations,
            List<HomeCard> hotIssues, List<HomeCard> nearbyPosts, String nearbyMode, int unreadCount) {}

    public record SearchPage(List<SearchItem> items, String nextCursor, boolean hasMore) {}
}
