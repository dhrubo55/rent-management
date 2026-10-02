CREATE TABLE manager_property_assignments (
  id BIGSERIAL PRIMARY KEY,
  manager_user_id BIGINT NOT NULL REFERENCES users(id),
  property_id BIGINT NOT NULL REFERENCES properties(id),
  CONSTRAINT uq_manager_property UNIQUE (manager_user_id, property_id)
);

CREATE TABLE privacy_cleanup_reports (
  id BIGSERIAL PRIMARY KEY,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  audit_rows_cleared BIGINT NOT NULL,
  notification_destinations_cleared BIGINT NOT NULL
);

INSERT INTO privacy_cleanup_reports (audit_rows_cleared, notification_destinations_cleared)
SELECT (SELECT COUNT(*) FROM audit_logs WHERE old_state_json IS NOT NULL OR new_state_json IS NOT NULL),
       (SELECT COUNT(*) FROM notification_events WHERE destination IS NOT NULL);

UPDATE audit_logs SET old_state_json = NULL, new_state_json = NULL
WHERE old_state_json IS NOT NULL OR new_state_json IS NOT NULL;
UPDATE notification_events SET destination = NULL WHERE destination IS NOT NULL;
