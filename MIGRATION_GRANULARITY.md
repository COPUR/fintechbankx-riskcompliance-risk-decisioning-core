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

