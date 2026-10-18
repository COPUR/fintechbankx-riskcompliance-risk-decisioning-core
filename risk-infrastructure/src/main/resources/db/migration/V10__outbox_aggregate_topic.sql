-- One Kafka topic per aggregate (ADR-019, owner decision 2026-10-08): every
-- RiskAssessment event goes to evt.rsk.risk.v1 and is named by its eventType
-- record header, so the per-event topic evt.rsk.risk.assessed.v1 is retired
-- before anything was published to it (ADR-019 section 8).
--
-- Rows still waiting for the relay, pending or parked, move to the aggregate
-- topic, so neither the relay nor a manual replay sends to the retired name.
-- Published rows keep the topic they were sent to and age out with the purge.
-- ck_outbox_topic_namespace (V3, evt.rsk.risk.%) already admits the new name.

UPDATE outbox_event
   SET topic = 'evt.rsk.risk.v1'
 WHERE published_at IS NULL
   AND topic = 'evt.rsk.risk.assessed.v1';

COMMENT ON COLUMN outbox_event.topic IS 'Aggregate topic evt.rsk.risk.v<N> (ADR-019); the event is named by event_type.';
