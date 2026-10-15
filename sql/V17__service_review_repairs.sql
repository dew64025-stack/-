-- B service review repairs; retain prior migrations and existing source metadata mappings.
ALTER TABLE resources ADD COLUMN reservation_mode VARCHAR(16) NOT NULL DEFAULT 'capacity',
  ADD COLUMN booking_blocked BOOLEAN NOT NULL DEFAULT FALSE;
-- One-time legacy repair: the old implementation derived mode from slot capacity, not parent capacity.
-- Preserve consistent historical slot meaning. Mixed/ambiguous data needs explicit administrator repair.
UPDATE resources r LEFT JOIN (
  SELECT resource_id,MAX(capacity IS NULL) AS has_exclusive,MAX(capacity IS NOT NULL) AS has_capacity
  FROM resource_slots GROUP BY resource_id
) s ON s.resource_id=r.id
SET r.reservation_mode=CASE WHEN s.has_exclusive=1 AND s.has_capacity=0 THEN 'exclusive' ELSE 'capacity' END,
    r.booking_blocked=CASE WHEN s.has_exclusive=1 AND s.has_capacity=1 THEN TRUE
      WHEN s.resource_id IS NULL AND r.capacity IS NULL THEN TRUE ELSE FALSE END;
UPDATE resources r SET booking_blocked=TRUE WHERE EXISTS (
  SELECT 1 FROM resource_slots s JOIN resource_occupancies o ON o.slot_id=s.id
  WHERE s.resource_id=r.id AND ((r.reservation_mode='exclusive' AND o.seat_no<>0)
    OR (r.reservation_mode='capacity' AND (o.seat_no=0 OR s.capacity IS NULL OR s.capacity<1 OR o.seat_no>s.capacity)))
);
ALTER TABLE reservations ADD COLUMN pending_expires_at DATETIME(3) NULL;
UPDATE reservations SET pending_expires_at=DATE_ADD(updated_at, INTERVAL 15 MINUTE) WHERE status='pending';
ALTER TABLE campus_issues ADD COLUMN region_id BIGINT UNSIGNED NULL,
  ADD COLUMN images_json JSON NULL,
  ADD CONSTRAINT fk_issues_region FOREIGN KEY(region_id) REFERENCES campus_regions(id);
ALTER TABLE campus_issue_evidences ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'pending';
CREATE TABLE campus_issue_events (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY, issue_id BIGINT UNSIGNED NOT NULL,
  from_status VARCHAR(32) NULL, to_status VARCHAR(32) NOT NULL,
  operator_id BIGINT UNSIGNED NOT NULL, comment VARCHAR(512) NULL, created_at DATETIME(3) NOT NULL,
  KEY idx_issue_events_issue(issue_id),
  CONSTRAINT fk_issue_events_issue FOREIGN KEY(issue_id) REFERENCES campus_issues(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE work_orders ADD COLUMN review_approved_at DATETIME(3) NULL;
