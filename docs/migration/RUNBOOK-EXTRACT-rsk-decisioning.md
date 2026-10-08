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

Flyway migrations: `risk-infrastructure/src/main/resources/db/migration/V1__create_risk_assessment.sql`, `V2__create_legacy_credit_risk_assessment.sql`, `V3__create_outbox.sql`, `V4__park_undeliverable_outbox_events.sql`. The service never reads monolith tables and the monolith must not read `sc_rsk_decisioning`.

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
| 1 | Deploy the service with `OUTBOX_RELAY_ENABLED=false`; run the backfill; reconcile | drop `sc_rsk_decisioning`, nothing else changed |
| 2 | Payments call `POST /api/v1/risk/assess` with the payment id as `transactionId` and a client-credentials token (`SERVICE` role), behind a flag | flag off; payments keep their rule-based screening |
| 3 | Monolith stops writing `risk_assessments`; re-run the backfill for late rows | monolith table is still intact |
| 4 | Create `evt.rsk.risk.assessed.v1` on the platform cluster, grant the IRSA role (`msk_cluster_arn`), enable the outbox relay | relay off; events stay in the outbox |
| 5 | After one full month-end cycle: drop the monolith table | restore from snapshot |

## 4. Parked outbox events

`OutboxRelay` parks a row (sets `parked_at`, keeps the reason in `last_error`) when Kafka refuses it permanently
(`RecordTooLargeException`, `SerializationException`, `InvalidTopicException`, `TopicAuthorizationException`, any
error that is not a Kafka `RetriableException`) or when it has failed `risk.outbox.relay.max-attempts` times
(`OUTBOX_RELAY_MAX_ATTEMPTS`, default 10). Retryable failures below the cap stop the batch and are retried on the
next run, as before. Parked rows are skipped, never purged, and counted by `outbox_parked_events{service="svc-rsk-decisioning"}`;
alert on any value above zero, because consumers are missing those decisions.

A long broker or network outage also parks rows: each head-of-queue row parks after the cap, then the next one
becomes the head. After such an outage, replay everything that was parked during it.

Replay, after fixing the cause (topic created, IAM policy fixed, payload size limit raised):

```sql
-- Inspect
SELECT event_id, created_seq, topic, attempts, last_error, parked_at
FROM sc_rsk_decisioning.outbox_event
WHERE published_at IS NULL AND parked_at IS NOT NULL
ORDER BY created_seq;

-- Un-park one row (or drop the event_id filter to replay all, in created_seq order);
-- attempts must be reset, otherwise the cap parks the row again on its first failure.
UPDATE sc_rsk_decisioning.outbox_event
SET parked_at = NULL, attempts = 0, last_error = NULL
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NOT NULL;
```

The relay publishes un-parked rows on its next run (`risk.outbox.relay.interval`, 1 s). Consumers de-duplicate on
`eventId`, so replaying a row that Kafka did in fact accept is safe. Record each replay (event ids, cause, operator)
in the change log of the environment.

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
