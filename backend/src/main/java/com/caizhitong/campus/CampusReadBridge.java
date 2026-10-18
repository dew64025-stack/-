package com.caizhitong.campus;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only campus existence checks used by public discovery endpoints. */
@Component
public class CampusReadBridge {
    private final JdbcTemplate jdbc;

    public CampusReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean regionExists(long campus, long regionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM campus_regions WHERE id=? AND campus_id=?"
                + " AND status='active' AND deleted_at IS NULL", Integer.class, regionId, campus) > 0;
    }

    public Long firstOpenCampus() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM campuses WHERE status='active' AND deleted_at IS NULL"
                + " ORDER BY id LIMIT 1", Long.class);
        return ids.isEmpty() ? null : ids.getFirst();
    }
}
