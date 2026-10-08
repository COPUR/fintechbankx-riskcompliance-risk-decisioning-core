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
- 2026-10-08: risk decisions are announced on `evt.rsk.risk.assessed.v1` through a transactional outbox (`V3__create_outbox.sql`); the AsyncAPI contract is owned here (`api/asyncapi/svc-rsk-decisioning.yaml`).
