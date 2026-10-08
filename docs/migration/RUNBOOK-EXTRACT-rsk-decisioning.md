# RUNBOOK-EXTRACT-rsk-decisioning

Extraction of risk decisioning from `enterprise-loan-management-system` into
`svc-rsk-decisioning` (this repository), following the strangler-fig steps of
`fbx-monolith-extraction`.

| Field | Value |
|---|---|
| Context / service | `rsk` / `svc-rsk-decisioning` |
| Slice | Transaction risk assessment (score, ALLOW / REVIEW / BLOCK, reasons) and the history of credit risk assessments |
| Owned data | `db_rsk_decisioning_<env>`, schema `sc_rsk_decisioning`: `risk_assessment`, `legacy_credit_risk_assessment`, `outbox_event` |
| Events | `evt.rsk.risk.assessed.v1` (`Risk.RiskAssessment.Assessed.v1`) through a transactional outbox; contract `api/asyncapi/svc-rsk-decisioning.yaml` (Proposed). Callers still use the decision synchronously. |
| Called by | `svc-pay-initiation-settlement` (namespace `payments`, client on `SERVICE_CALLERS`): `POST /api/v1/risk/assess`, `GET /api/v1/risk/assessments/{transactionId}`; bank staff read assessments |

## 1. Data ownership split

| Monolith object | Owner after the split | Notes |
|---|---|---|
| `risk_assessments` (V15, credit risk of loans and applications) | this service, `legacy_credit_risk_assessment` | read-only history; FKs to `customers`, `loans`, `loan_applications` dropped, ids kept as text |
| `risk-context` JPA adapter (`risk_assessments` with transaction columns) | replaced | it mapped columns that V15 never had, so it never stored anything; transaction decisions now live in `risk_assessment` |
| `customers`, `loans`, `loan_applications` | `svc-cus-profile-kyc`, `svc-ln-loan-lifecycle` | never copied here |
| Fraud ML and vendor clients (`infrastructure/fraud` in the payments repo) | belongs here, not ported yet | payments screens with its rule-based adapter until this service exposes a model-backed score |

Flyway migrations: `risk-infrastructure/src/main/resources/db/migration/V1__create_risk_assessment.sql`, `V2__create_legacy_credit_risk_assessment.sql`, `V3__create_outbox.sql`, `V4__park_undeliverable_outbox_events.sql`, `V6__outbox_first_failed_at.sql`. The service never reads monolith tables and the monolith must not read `sc_rsk_decisioning`.

## 2. Backfill and reconciliation

`db/backfill/run-backfill.sh "<monolith conninfo>" "<risk service conninfo>"`

1. Exports `risk_assessments` in one read-only snapshot.
2. Stages them in `backfill_stage` and copies them into `legacy_credit_risk_assessment` (`02_transform_into_risk_service.sql`). Every column is kept; `customer_id` becomes text, the id the customer service keeps.
3. Compares row counts, exposure and expected-loss totals, and checks each row's expected loss against EAD × PD × LGD (`03_reconcile.sql`). Any difference fails the run.

The backfill is independent of the other contexts' backfills and can be re-run until cutover:
- A row already copied is updated when anything changed in the monolith since the last run, for example an assessment approved or overridden later (`status`, `approved_by`, `approval_date`, `override_*`, review dates, `updated_at`, `version`). Unchanged rows are not touched.
- The service never writes `legacy_credit_risk_assessment`, so the monolith's version always wins.
- `03_reconcile.sql` compares totals, the expected-loss invariant, and every column of every row against the snapshot (`IS DISTINCT FROM`). Any `MISSING` or `DIFF` row fails the run.
- `scripts/migration/verify-backfill.sh` rehearses two runs, an approval in the monolith with a third run, and a drifted row that reconciliation must catch, on a scratch PostgreSQL. It runs in CI (`deploy/data-split-rehearsal`).

## 3. Cutover plan

| Step | Action | Rollback |
|---|---|---|
| 1 | Deploy the service with the chart default `config.OUTBOX_RELAY_ENABLED: "false"` (no MSK egress is needed yet); run the backfill; reconcile | drop `sc_rsk_decisioning`, nothing else changed |
| 2 | Payments call `POST /api/v1/risk/assess` with the payment id as `transactionId` and a client-credentials token (`SERVICE` role), behind a flag | flag off; payments keep their rule-based screening |
| 3 | Monolith stops writing `risk_assessments`; re-run the backfill for late rows | monolith table is still intact |
| 4 | Preconditions: the mesh contract (fintechbankx-platform-mesh-security-service-mesh `contracts/mesh-contract.yaml`) lists `msk` in the datastores of `risk-decisioning-service` and `allow-egress-msk` is generated for namespace `risk`; asyncapi-catalog #11 (the catalog entry for
`api/asyncapi/svc-rsk-decisioning.yaml`) is merged; `evt.rsk.risk.assessed.v1` exists on the platform cluster; the IRSA role is granted (`msk_cluster_arn`). Then enable the relay: `helm upgrade ... --set config.OUTBOX_RELAY_ENABLED=true` (or the same key in the environment's values file). Check `outbox_pending_events` falls to zero and `outbox_parked_events` stays zero | `--set config.OUTBOX_RELAY_ENABLED=false`; events stay in the outbox |
| 5 | After one full month-end cycle: drop the monolith table | restore from snapshot |

## 4. Parked outbox events

`OutboxRelay` follows ADR-021 decision 4 (fintechbankx-governance-architecture-enablement-adr-runbooks):

- Payload errors (`RecordTooLargeException`, `SerializationException`, `InvalidTopicException`): park the row
  (`parked_at` set, reason in `last_error`, alert raised through `outbox_parked_events`) and continue with the
  next row.
- Everything else, including retriable, authorization and SASL/IAM failures and any unclassified exception: stop
  the batch without marking the row or anything after it, retry with backoff and alert. The relay never skips or
  parks a row for such an error, however long it lasts. Backoff after a stopped batch starts at
  `risk.outbox.relay.interval` (1 s) and doubles per stopped run up to `risk.outbox.relay.max-backoff`
  (`OUTBOX_RELAY_MAX_BACKOFF`, default `PT5M`); any run that does not stop resets it.

An outage or a credential problem therefore only delays events: fix the cause (the relay's WARN log names the
exception) and the relay catches up by itself. `first_failed_at` (V6) is no longer written.

Alerts:
- `outbox_oldest_pending_age_seconds`: age of the oldest row waiting for the relay; the signal for a stalled relay,
  an outage or a broken credential.
- `outbox_parked_events{service="svc-rsk-decisioning"}`: alert on any value above zero, because consumers are
  missing those decisions until they are replayed.

Manual park (operator only, change-logged). If one row is stuck on a non-payload error that only it triggers and
the owning squad decides to let later events through, park it by hand with the reason; the relay then skips it:

```sql
UPDATE sc_rsk_decisioning.outbox_event
SET parked_at = now(), last_error = left('manual: <reason, ticket>', 512)
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NULL;
```

Replay, after fixing the cause (topic created, IAM policy fixed, payload size limit raised), for rows parked by the
relay or by hand:

```sql
-- Inspect
SELECT event_id, created_seq, topic, attempts, last_error, parked_at
FROM sc_rsk_decisioning.outbox_event
WHERE published_at IS NULL AND parked_at IS NOT NULL
ORDER BY created_seq;

-- Un-park one row (or drop the event_id filter to replay all, in created_seq order).
UPDATE sc_rsk_decisioning.outbox_event
SET parked_at = NULL, first_failed_at = NULL, attempts = 0, last_error = NULL
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NOT NULL;
```

The relay publishes un-parked rows on its next run. Consumers de-duplicate on `eventId`, so replaying a row that
Kafka did in fact accept is safe. Record each manual park and replay (event ids, cause, operator) in the change log
of the environment.

## 5. Acceptance checklist

- [x] Service builds and tests standalone (`ci/build`, `ci/test`, including PostgreSQL integration tests)
- [x] Own schema and migrations; Hibernate validates the entity at startup
- [x] One decision per transaction: retries that repeat every input return the stored decision; a reused id with any other input (amount, currency, high-risk flag, velocity) is a 409; a lost concurrent insert is a retryable 409 with no event left behind
- [x] Credit risk history backfill rehearsed with reconciliation in CI
- [x] Container image, Helm chart, Terraform validate in CI (`Deployability` workflow)
- [ ] Payment services call this API (follow-up in the payments repositories)
- [x] Risk decision events written through a transactional outbox (one row per new assessment, none on retries or lost races), relayed in order with one active relay
- [ ] Topic `evt.rsk.risk.assessed.v1` created on the platform cluster (fintechbankx-platform-event-streaming-kafka) and the catalog mirror updated
- [ ] Model-backed fraud score (port of the payments `infrastructure/fraud` package)
- [ ] Production backfill and reconciliation report attached here
