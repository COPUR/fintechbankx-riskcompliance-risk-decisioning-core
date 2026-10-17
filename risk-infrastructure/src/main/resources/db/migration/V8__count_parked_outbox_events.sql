-- Each parked row is counted once by the outbox_parked_events_total counter
-- (platform alert OutboxEventsParked). The relay writes the rows it parks with
-- park_counted = true in the same update; rows an operator parks by hand (runbook
-- "Manual park") start uncounted, and the relay's next run counts them as
-- OperatorPark and marks them. A replay resets the flag with parked_at.

ALTER TABLE outbox_event ADD COLUMN park_counted BOOLEAN NOT NULL DEFAULT FALSE;

-- Rows parked before this migration are not counted retroactively.
UPDATE outbox_event SET park_counted = TRUE WHERE parked_at IS NOT NULL;

COMMENT ON COLUMN outbox_event.park_counted IS 'TRUE once outbox_parked_events_total has counted this park; reset to FALSE on replay.';

CREATE INDEX ix_outbox_park_uncounted ON outbox_event (created_seq) WHERE parked_at IS NOT NULL AND NOT park_counted;
