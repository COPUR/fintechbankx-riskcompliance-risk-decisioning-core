-- first_failed_at: when a row's sends started failing. Not written by the
-- relay under ADR-021 decision 4 (a non-payload failure marks nothing on the
-- row, and the oldest-pending-age gauge reads created_at); kept as a nullable
-- column that the replay SQL resets.

ALTER TABLE outbox_event ADD COLUMN first_failed_at TIMESTAMPTZ;

COMMENT ON COLUMN outbox_event.first_failed_at IS 'First failed send of the row; reset to NULL with parked_at on a manual replay.';
