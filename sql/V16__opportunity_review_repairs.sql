-- Preserve per-item checks when requirements become immutable checklist snapshots.
ALTER TABLE opportunity_material_items ADD COLUMN validation_rules_json JSON NULL;
ALTER TABLE material_checklist_items ADD COLUMN validation_rules_json JSON NULL;
-- Explicit human structural editing can confirm a deliberate empty array.
ALTER TABLE opportunities
 ADD COLUMN eligibility_reviewed BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN materials_reviewed BOOLEAN NOT NULL DEFAULT FALSE;
