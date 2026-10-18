# Migration Granularity Notes

- Repository: `fintechbankx-risk-decisioning-service`
- Source monorepo: `enterprise-loan-management-system`
- Sync date: `2026-03-15`
- Sync branch: `chore/granular-source-sync-20260313`

## Applied Rules

- dir: `risk-context` -> `.`
- file: `api/openapi/risk-context.yaml` -> `api/openapi/risk-context.yaml`

## Notes

- This is an extraction seed for bounded-context split migration.
- Follow-up refactoring may be needed to remove residual cross-context coupling.
- Build artifacts and local machine files are excluded by policy.
- 2026-10-07: seed turned into a runnable service. The service owns `sc_rsk_decisioning` with its own Flyway migrations; the monolith's `risk_assessments` rows move with `db/backfill/run-backfill.sh` (see `docs/migration/RUNBOOK-EXTRACT-rsk-decisioning.md`).
- 2026-10-08: risk decisions are announced on a per-event topic through a transactional outbox (`V3__create_outbox.sql`); the AsyncAPI contract is owned here (`api/asyncapi/svc-rsk-decisioning.yaml`).
- 2026-10-10: one topic per aggregate (ADR-019, owner decision 2026-10-08): every `RiskAssessment` event goes to `evt.rsk.risk.v1` with the `eventType`, `eventId` and `correlationId` record headers; `V10__outbox_aggregate_topic.sql` moves pending and parked outbox rows from the retired per-event topic. Nothing had been published, so there is no dual-run.
- 2026-10-10: Flyway moves out of the service pods into the chart's pre-install/pre-upgrade Job (`docs/architecture/decisions/0002-flyway-runs-in-a-migration-job.md`): the Job migrates as the schema owner (`db-migration` secret), the pods connect as the runtime role `V11__grant_runtime_role_least_privilege.sql` grants and only validate the schema at startup. Every local or ephemeral start runs `migrate` before the application.
- 2026-10-18: screening facts computed by their owner (CRC plan). `POST /api/v2/risk/assess` takes ids and amounts; risk resolves the counterparty country against its own `high_risk_country` table (`V12__create_high_risk_country.sql`, loaded by `scripts/ops/load-high-risk-countries.sh`) and the velocity from its own decisions; `V13__record_risk_assessment_customer_and_counterparty_country.sql` stores the customer and the counterparty country with the decision and admits `attestation_source` `RISK_RESOLVED`. v1 assess is deprecated behind `RISK_V1_CALLER_FACTS_ENABLED`. `POST /api/v1/risk/loan-eligibility` moves the loan eligibility rule from the customer context here (`LoanEligibilityPolicy`, parity with `Customer.isEligibleForLoan`).
