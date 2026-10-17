-- Existing members/applications retain their unknown role; captains establish stable roles
-- through a versioned openRoles update. No historical confirmation is manufactured.
CREATE TABLE team_open_roles (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  team_id BIGINT UNSIGNED NOT NULL,
  role VARCHAR(64) NOT NULL,
  skill_tags JSON NULL,
  quantity INT UNSIGNED NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_team_role (team_id,id),
  CONSTRAINT fk_team_role_team FOREIGN KEY (team_id) REFERENCES teams(id),
  CONSTRAINT chk_team_role_quantity CHECK (quantity BETWEEN 1 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE team_applications ADD COLUMN open_role_id BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_team_application_role FOREIGN KEY (team_id,open_role_id) REFERENCES team_open_roles(team_id,id);
ALTER TABLE team_members ADD COLUMN open_role_id BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_team_member_role FOREIGN KEY (team_id,open_role_id) REFERENCES team_open_roles(team_id,id);
CREATE TABLE lost_found_events (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  match_id BIGINT UNSIGNED NOT NULL,
  actor_id BIGINT UNSIGNED NOT NULL,
  action VARCHAR(32) NOT NULL,
  description VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_lost_party_action (match_id,actor_id,action),
  CONSTRAINT fk_lost_event_match FOREIGN KEY (match_id) REFERENCES lost_found_matches(id),
  CONSTRAINT fk_lost_event_actor FOREIGN KEY (actor_id) REFERENCES users(id),
  CONSTRAINT chk_lost_event_action CHECK (action IN ('verify','confirm_return','ignore'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
