-- Outbox rows that can never be sent are parked instead of blocking every
-- later event. OutboxRelay parks a row when Kafka refuses it permanently
-- (RecordTooLarge, Serialization, InvalidTopic, TopicAuthorization, any error
-- that is not retriable) or when it has failed risk.outbox.relay.max-attempts
-- times. Parked rows are skipped by the relay, never purged, counted by the
-- outbox_parked_events gauge, and replayed by hand (runbook "Parked outbox
-- events"). last_error (V3) keeps the reason.

ALTER TABLE outbox_event ADD COLUMN parked_at TIMESTAMPTZ;

COMMENT ON COLUMN outbox_event.parked_at IS 'Set when the relay gave up on the row; NULL again after a manual replay.';

-- The relay reads rows that are neither published nor parked, in insertion order.
DROP INDEX ix_outbox_unpublished;
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_seq) WHERE published_at IS NULL AND parked_at IS NULL;
CREATE INDEX ix_outbox_parked ON outbox_event (parked_at) WHERE published_at IS NULL AND parked_at IS NOT NULL;
