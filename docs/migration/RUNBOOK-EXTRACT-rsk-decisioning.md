# RUNBOOK-EXTRACT-rsk-decisioning

Extraction of risk decisioning from `enterprise-loan-management-system` into
`svc-rsk-decisioning` (this repository), following the strangler-fig steps of
`fbx-monolith-extraction`.

| Field | Value |
|---|---|
| Context / service | `rsk` / `svc-rsk-decisioning` |
| Slice | Transaction risk assessment (score, ALLOW / REVIEW / BLOCK, reasons) and the history of credit risk assessments |
| Owned data | `db_rsk_decisioning_<env>`, schema `sc_rsk_decisioning`: `risk_assessment`, `legacy_credit_risk_assessment`, `outbox_event` |
| Events | `Risk.RiskAssessment.Assessed.v1` on the aggregate topic `evt.rsk.risk.v1` (ADR-019: one topic per aggregate, key = assessment id, headers `eventType`, `eventId`, `correlationId`, plus `traceparent` when traced; consumers skip eventTypes they do not handle) through a transactional outbox; contract `api/asyncapi/svc-rsk-decisioning.yaml` (Proposed). Callers still use the decision synchronously. |
| Called by | `svc-pay-initiation-settlement` (namespace `payments`, client on `SERVICE_CALLERS`): `POST /api/v1/risk/assess`, `GET /api/v1/risk/assessments/{transactionId}`; bank staff read assessments |

## 1. Data ownership split

| Monolith object | Owner after the split | Notes |
|---|---|---|
| `risk_assessments` (V15, credit risk of loans and applications) | this service, `legacy_credit_risk_assessment` | read-only history; FKs to `customers`, `loans`, `loan_applications` dropped, ids kept as text |
| `risk-context` JPA adapter (`risk_assessments` with transaction columns) | replaced | it mapped columns that V15 never had, so it never stored anything; transaction decisions now live in `risk_assessment` |
| `customers`, `loans`, `loan_applications` | `svc-cus-profile-kyc`, `svc-ln-loan-lifecycle` | never copied here |
| Fraud ML and vendor clients (`infrastructure/fraud` in the payments repo) | belongs here, not ported yet | payments screens with its rule-based adapter until this service exposes a model-backed score |

Flyway migrations: `risk-infrastructure/src/main/resources/db/migration/V1__create_risk_assessment.sql` to `V11__grant_runtime_role_least_privilege.sql`. They are applied by the chart's migration Job as the schema owner, never by the service pods (section "Database migrations (Flyway Job)" below). V5 adds `attestation_source` and `attested_by`, both NOT NULL without a default: every decision of record states them. V5 was edited in place before any release (it had run only in CI, ephemeral and local test databases): a database where the earlier V5 already ran fails Flyway validation on the checksum, so recreate it (drop `sc_rsk_decisioning`) or, only if it holds no decisions, run `flyway repair` after applying the NOT NULL change by hand. V7 backfills `rule_set_version = 'rsk-policy-v1'` and then drops the column default, so every new row must state it (`rule_set_version` is NOT NULL). V9 adds `risk_assessment.payment_type`, NOT NULL without a default (no decision of record is stored persistently yet; recreate any local test database). V8 adds `outbox_event.park_counted` and backfills it to TRUE for rows already parked, so they are not counted again. The service never reads monolith tables and the monolith must not read `sc_rsk_decisioning`. V11 grants the runtime role (section "Database roles").

### Database roles (DBA bootstrap)

| Role | Used by | Privileges |
|---|---|---|
| migration owner (`risk_decisioning_owner`, secret `<env>/risk-decisioning-service/db-migration`, Terraform output `migration_db_secret_name`) | the Flyway migration Job only (`DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD`, Helm `externalSecret.migrationSecretName`, required); the service pods never mount it | owns `sc_rsk_decisioning` and its objects; needs `CREATE` on the database |
| `risk_decisioning_app` (runtime, `DB_USERNAME`, secret `<env>/risk-decisioning-service/db-app`) | the service | granted by V11 (Flyway placeholder `runtime_role`): `USAGE` on the schema; `SELECT, INSERT` on `risk_assessment` (no `UPDATE`, `DELETE`, `TRUNCATE`); `SELECT` on `legacy_credit_risk_assessment`; `SELECT, INSERT, UPDATE, DELETE` on `outbox_event` (the relay marks, parks and purges rows); `SELECT` on `flyway_schema_history` (the service validates at startup). Not the owner, so it cannot `ALTER`/`DROP` the tables nor record a migration (`RiskServiceIT.theRuntimeRoleCanOnlyReadAndInsertDecisionsAndWorkTheOutbox`) |
| backfill role | `db/backfill/run-backfill.sh` | `CREATE` on the database (staging schema); `SELECT, INSERT, UPDATE, DELETE` on `legacy_credit_risk_assessment` only |

`risk_assessment` is insert-only for the runtime role by grants alone (there is no insert-only trigger as in compliance): the owner can still change rows. Its credential is mounted only by the migration Job, and the hook deletes it from the namespace after a successful run ([decision 0002](../architecture/decisions/0002-flyway-runs-in-a-migration-job.md)). The guarantee therefore holds once the DBA bootstrap has created separate roles in the environment. A DBA (or the RDS master user) can always change rows; tamper evidence against them is not built.

DBA bootstrap, per environment, before the first deploy: create both roles (the owner with `CREATE` on `db_rsk_decisioning_<env>`, the runtime role with `LOGIN` only, no membership in the owner), write their `{"username","password"}` to the two secrets Terraform creates, and set Helm `externalSecret.migrationSecretName` (the chart does not render without it). The migration Job then migrates as the owner, and V11 grants the runtime role.

**Local and ephemeral environments must run `migrate` before the application starts.** The service only validates the schema, so an empty or unmigrated database does not start it. Local single-user runs (`./gradlew bootRun`, no `DB_MIGRATION_*`) have no Job: run `./gradlew :risk-bootstrap:bootRun --args=migrate` (or `java -jar risk-decisioning-service.jar migrate`) first, then the application without the argument. V11 then changes nothing (the runtime role is the connecting user). CI follows the same rule: the integration tests migrate as the owner in their test context, and the backfill rehearsal applies the migrations with psql; nothing boots the jar against an unmigrated database.

### Database migrations (Flyway Job)

Flyway never runs in the service pods ([decision 0002](../architecture/decisions/0002-flyway-runs-in-a-migration-job.md)). The chart's `templates/migration-job.yaml` is a Helm `pre-install` and `pre-upgrade` hook Job. It runs the service image with the argument `migrate` (`DatabaseMigration`: datasource, Flyway and `DatabaseTlsGuard` only), migrates as the schema owner through the verified `DB_URL`, and exits 0, or 1 on any failure. Its pods are labelled `app.kubernetes.io/name=risk-decisioning-service` (the mesh's Aurora egress) and `app.kubernetes.io/component=db-migration`, which no Service, PDB or topology spread selects (cicd-templates 335a345), and `sidecar.istio.io/inject: "false"`, written after `podLabels` so it overrides them: Aurora egress is a Kubernetes NetworkPolicy on the name label, so the Job needs no proxy, and without native sidecars an injected proxy would keep the Job from completing. The service pods have only the runtime role. At startup they validate the schema history and refuse to start while a migration is pending (`FlywayValidateException` in the log).

Deploy order, on `helm install` and on every `helm upgrade`:

1. Hooks at weight -10: the `risk-decisioning-service-db-migration` ExternalSecret (External Secrets syncs the owner credential into Secret `risk-decisioning-service-db-migration`) and the Job's ServiceAccount of the same name (no IAM role, no API token).
2. Hook at weight 0: Job `risk-decisioning-service-db-migration`. Its pod waits in `CreateContainerConfigError` until the Secret exists, then migrates. `backoffLimit` 1, `activeDeadlineSeconds` 600, `ttlSecondsAfterFinished` 86400 (values `migrationJob`).
3. Only after the Job succeeds: Helm deletes the hook ExternalSecret and ServiceAccount (the Secret goes with them), then creates or updates the regular resources (ConfigMap, Deployment and the others). New pods validate and start.

Use `helm upgrade --install ... --timeout 15m`. Helm's default 5 min is shorter than the Job's 600 s deadline plus the rollout, so Helm would give up while the Job is still running.

A failed Job blocks the rollout. Helm marks the install or upgrade failed and does not touch the Deployment. On an upgrade the old pods keep serving on the old schema; on a first install nothing is deployed. Flyway applies each migration in its own transaction, so a failed migration leaves the schema at the last applied version and marks nothing as applied. To diagnose:

```bash
kubectl -n risk get job,pod -l app.kubernetes.io/component=db-migration
kubectl -n risk logs job/risk-decisioning-service-db-migration --all-containers
kubectl -n risk describe job risk-decisioning-service-db-migration   # DeadlineExceeded, BackoffLimitExceeded
kubectl -n risk get externalsecret risk-decisioning-service-db-migration   # SecretSynced?
```

Common causes: the owner secret is not filled or not synced (the pod stays in `CreateContainerConfigError` until the deadline), `rds-ca-bundle` is missing (`ContainerCreating`), Aurora egress is not granted, `DatabaseTlsGuard` refused the URL, or a migration failed (SQL error in the log). A pod stuck `Running` with the migration finished means a sidecar was injected after all: check the pod's labels.

To re-run, fix the cause and run the same `helm upgrade` again. The `before-hook-creation` policy deletes the old Job, ExternalSecret and ServiceAccount and creates new ones. The ExternalSecret and the owner Secret stay in the namespace after a failed run, until that re-run or until you delete them (`kubectl -n risk delete externalsecret risk-decisioning-service-db-migration`). Never repair by giving the service pods the owner credential. If the history needs `flyway repair` (a changed checksum, or a migration that failed outside a transaction), the DBA runs it as the owner after review, outside the cluster.

`helm rollback` runs no hook. An older image validates against a newer history: Flyway ignores applied migrations it does not know. A rollback therefore starts as long as the newer migrations were additive. A migration that an older image cannot run on is a release decision: write it expand/contract style.

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

Deployment prerequisite (Aurora TLS): `config.DB_URL` carries `sslmode=verify-full` and `sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem` exactly once each and no `sslfactory`, `sslhostnameverifier` or `sslpasswordcallback`, and no other config key holds a database URL (`SPRING_DATASOURCE_*URL`, including `spring.datasource.hikari.jdbc-url`, `SPRING_FLYWAY_URL`, `SPRING_APPLICATION_JSON`), none sets a Spring config location or import that could load another URL (`SPRING_CONFIG_IMPORT`, `SPRING_CONFIG_ADDITIONAL_LOCATION`, `SPRING_CONFIG_LOCATION`, with or without an index suffix), none sets JVM options (`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `JAVA_OPTS`: they would set `spring.datasource.url` or load an agent before `application.yml` is read; the image fixes them) and none sets a log level (`LOGGING_LEVEL_*`: `LOGGING_LEVEL_ORG_POSTGRESQL=TRACE` would write the wire protocol, with row data, to the pod log). Keys are matched after normalisation (upper case, every non-alphanumeric character dropped), so dash, dot, underscore and index spellings are all caught. The chart renders no Spring config import of its own; a configtree would be allowed only as a chart-rendered value on the fixed mount `optional:configtree:/etc/fintechbankx/config/`, never from a values key. The chart has no `extraEnv` or `env` list, so the ConfigMap is the only route for a config key. The chart refuses to render otherwise, and with `DB_SSL_ROOT_CERT` set (always, in the chart) the service's `DatabaseTlsGuard` refuses to start on any datasource or Flyway URL that PgJDBC would not verify against that bundle.

| Step | Action | Rollback |
|---|---|---|
| 1 | Preconditions: DBA bootstrap of both database roles done and their secrets filled, `externalSecret.migrationSecretName` set (section 1, "Database roles"); ConfigMap `rds-ca-bundle` (key `global-bundle.pem`, published by the platform's trust-manager Bundle) exists in namespace `risk`, and `config.DB_URL` is the Terraform `jdbc_url` output with `sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem` (cicd-templates 4f0f266). The chart refuses to render a PostgreSQL URL without `sslmode=verify-full`, and a missing bundle keeps the pods from starting. Install order: cert-manager, trust-manager and the `rds-ca-bundle` Bundle must be installed before this chart; a pod scheduled earlier stays `ContainerCreating` until the ConfigMap appears. Deploy with `helm upgrade --install ... --timeout 15m` and the chart default `config.OUTBOX_RELAY_ENABLED: "false"` (no MSK egress is needed yet); the migration Job runs first and must succeed before any pod starts (section 1, "Database migrations (Flyway Job)"); run the backfill; reconcile | drop `sc_rsk_decisioning`, nothing else changed |
| 2 | Precondition: `highRiskCountry` and `velocityScore` have a real source in the caller (not caller constants); see the ADR 0001 follow-ups. Parity and test runs may use constants. Payments send `paymentType` (their PaymentType value; required since `rsk-policy-v3`). Release order: payments 8a1a3ea, which sends `paymentType`, deploys before or with this risk head; an older payments build gets a 400 for every assessment. Then payments call `POST /api/v1/risk/assess` with the payment id as `transactionId` and a client-credentials token (`SERVICE` role), behind a flag. Network path: the service-mesh repo's `allow-ingress-from-payments` in namespace `risk` (mesh #11); this chart ships no NetworkPolicy | flag off; payments keep their rule-based screening |
| 3 | Monolith stops writing `risk_assessments`; re-run the backfill for late rows | monolith table is still intact |
| 4 | Preconditions: the mesh contract (fintechbankx-platform-mesh-security-service-mesh `contracts/mesh-contract.yaml`) lists `msk` in the datastores of `risk-decisioning-service` and `allow-egress-msk` is generated for namespace `risk`; asyncapi-catalog #11 (the catalog entry for
`api/asyncapi/svc-rsk-decisioning.yaml`) is merged; the aggregate topic `evt.rsk.risk.v1` exists on the platform cluster (no per-event topic is needed); the IRSA role is granted (`msk_cluster_arn`). Then enable the relay: `helm upgrade ... --set config.OUTBOX_RELAY_ENABLED=true` (or the same key in the environment's values file). Check `outbox_pending_events` falls to zero and `outbox_parked_rows` stays zero | `--set config.OUTBOX_RELAY_ENABLED=false`; events stay in the outbox |
| 5 | After one full month-end cycle: drop the monolith table | restore from snapshot |

Existing release: the chart's selector labels now include `app.kubernetes.io/component: service`, and a Deployment's
`spec.selector` is immutable, so `helm upgrade` of a release installed before that change fails. Nothing is installed
today; if a release exists, delete the Deployment (`kubectl -n risk delete deployment risk-decisioning-service`)
and run `helm upgrade` again to recreate it. Do this in a window: the service is unavailable between the delete and
the new pods becoming ready (the PodDisruptionBudget does not cover a deleted Deployment).

## 4. Parked outbox events

`OutboxRelay` follows ADR-021 decision 4 (fintechbankx-governance-architecture-enablement-adr-runbooks):

- Payload errors (`RecordTooLargeException`, `SerializationException`, `InvalidTopicException`): park the row
  (`parked_at` set, reason in `last_error`, counted once in `outbox_parked_events_total`) and continue with the
  next row.
- Everything else, including retriable, authorization and SASL/IAM failures and any unclassified exception: stop
  the batch without marking the row or anything after it, retry with backoff and alert. The relay never skips or
  parks a row for such an error, however long it lasts. Backoff after a stopped batch starts at
  `risk.outbox.relay.interval` (1 s) and doubles per stopped run up to `risk.outbox.relay.max-backoff`
  (`OUTBOX_RELAY_MAX_BACKOFF`, default `PT5M`); any run that does not stop resets it.

An outage or a credential problem therefore only delays events: fix the cause (the relay's WARN log names the
exception) and the relay catches up by itself. `first_failed_at` (V6) is no longer written.

Alerts (owning squad: `risk`, the Risk and Compliance Decisioning Squad). The rules live in the platform observability
repository (fintechbankx-platform-observability-sre-operations) PR #11 at commit `eca7aa0`, not merged yet; this
service ships no alert rule and this chart ships no PrometheusRule. The rules key on the `service_id` label, taken from
the pod label `fintechbankx.io/service-id` (`svc-rsk-decisioning`, set by this chart and asserted in the deployability
job), and route by squad:
- **OutboxRelayStalled** (stalled relay, outage or broken credential):
  `max(outbox_oldest_pending_age_seconds{service_id="svc-rsk-decisioning"}) > 900` for 5m, severity critical. The age
  is measured from `created_at` of the oldest row waiting for the relay.
- **OutboxSendFailures** (why it is stalled): any increase of
  `outbox_send_failures_total{service_id="svc-rsk-decisioning"}` over 10m, severity warning (tag `exception` names the
  cause).
- **OutboxEventsParked**: any increase of `outbox_parked_events_total` over 15m, with no `for` clause, severity
  warning. Operator parks also fire it. The counter (tag `exception`: the exception class, or `OperatorPark` for a
  manual park) counts each parked row once; `outbox_parked_rows` is the gauge of rows parked now. Consumers are missing
  those decisions until the rows are replayed.
- Dependency: the same PR widens the AMP remote-write keep regex to the `outbox_` series, so they reach the managed
  Prometheus once it merges. Scraping relies on the pod's `prometheus.io/*` annotations, which the PodMonitor reads;
  keep them.

Manual park (operator only, change-logged). If one row is stuck on a non-payload error that only it triggers and
the owning squad decides to let later events through, park it by hand with the reason. The relay then skips it, and
its next run counts it once in `outbox_parked_events_total{exception="OperatorPark"}`:

```sql
UPDATE sc_rsk_decisioning.outbox_event
SET parked_at = now(), last_error = left('manual: <reason, ticket>', 512)
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NULL;
```

Replay, after fixing the cause (topic created, IAM policy fixed, payload size limit raised), for rows parked by the
relay or by hand:

```sql
-- Inspect. Since V10 every unpublished row targets evt.rsk.risk.v1; the event is named by event_type.
SELECT event_id, created_seq, event_type, topic, attempts, last_error, parked_at
FROM sc_rsk_decisioning.outbox_event
WHERE published_at IS NULL AND parked_at IS NOT NULL
ORDER BY created_seq;

-- Un-park one row (or drop the event_id filter to replay all, in created_seq order).
UPDATE sc_rsk_decisioning.outbox_event
SET parked_at = NULL, park_counted = FALSE, first_failed_at = NULL, attempts = 0, last_error = NULL
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NOT NULL;
```

Replay resets `park_counted`, so a row parked again later is counted again. The relay publishes un-parked rows on
its next run. Consumers de-duplicate on `eventId`, so replaying a row that
Kafka did in fact accept is safe. Record each manual park and replay (event ids, cause, operator) in the change log
of the environment.

## 5. Acceptance checklist

- [x] Service builds and tests standalone (`ci/build`, `ci/test`, including PostgreSQL integration tests)
- [x] Own schema and migrations, applied by the chart's migration Job as the owner; the service validates the schema history and Hibernate validates the entity at startup
- [x] Runtime role separation: V11 grants, Flyway `DB_MIGRATION_*` credentials, Helm migration secret and hook Job, Terraform secret; in the IT the runtime role cannot change or remove a decision or record a migration (`RiskServiceIT`, `DatabaseMigrationIT`); effective in an environment only after the DBA bootstrap and the migration Job have run there
- [ ] DBA bootstrap of `risk_decisioning_owner` and `risk_decisioning_app` per environment, secrets filled
- [ ] Migration Job run on a cluster (Proposed: checked by render, kubeconform and the CI mutation checks only)
- [x] One decision per transaction: retries that repeat every input return the stored decision; a reused id with any other input (amount, currency, high-risk flag, velocity) is a 409; a lost concurrent insert is a retryable 409 with no event left behind
- [x] Credit risk history backfill rehearsed with reconciliation in CI
- [x] Container image, Helm chart, Terraform validate in CI (`Deployability` workflow)
- [ ] Payment services call this API (follow-up in the payments repositories)
- [x] Risk decision events written through a transactional outbox (one row per new assessment, none on retries or lost races), relayed in order with one active relay
- [ ] Topic `evt.rsk.risk.v1` created on the platform cluster (fintechbankx-platform-event-streaming-kafka) and the catalog mirror updated
- [ ] Model-backed fraud score (port of the payments `infrastructure/fraud` package)
- [ ] Production backfill and reconciliation report attached here
