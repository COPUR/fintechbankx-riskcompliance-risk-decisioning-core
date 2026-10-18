# 0002. Flyway runs in a Helm-hooked migration Job; the service pods only validate

- Status: Proposed
- Date: 2026-10-10
- Owner: Risk and Compliance Decisioning Squad
- Scope: `svc-rsk-decisioning`, chart `deploy/helm/risk-decisioning-service`, schema `sc_rsk_decisioning`

## Context

`risk_assessment` holds the decisions of record: written once per transaction,
never updated, and returned on every retry. Until this decision Flyway ran at
service startup as the one application credential, which therefore owned the
schema. Anyone with a pod's environment (a compromised pod, `kubectl exec`, a
leaked env dump) could act as the owner: change or delete a decision, alter a
table, or rewrite the schema history. The same review that found this in the
compliance service (its decision 0002) applies here.

The platform convention (cicd-templates 335a345) already expects services to
run Flyway as a Job: its pods carry `app.kubernetes.io/name=<service account>`
(the mesh grants Aurora egress on that label) and
`app.kubernetes.io/component=db-migration`, and every app selector includes
`app.kubernetes.io/component=service`, so the Job's pods are never selected.

## Decision

1. **Two database roles.** The migration owner (`risk_decisioning_owner`,
   Secrets Manager `<env>/risk-decisioning-service/db-migration`, Terraform
   output `migration_db_secret_name`) owns `sc_rsk_decisioning`. The runtime
   role (`risk_decisioning_app`, `db-app`) gets from V11 only `USAGE` on the
   schema, `SELECT, INSERT` on `risk_assessment`, `SELECT` on
   `legacy_credit_risk_assessment`, `SELECT, INSERT, UPDATE, DELETE` on
   `outbox_event` (the relay marks, parks and purges rows) and `SELECT` on
   `flyway_schema_history`. Flyway connects as the owner
   (`DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD`) through the same
   verified `DB_URL`; without those variables (local single-user runs) it uses
   the app credentials and V11 grants nothing.
2. **Flyway runs only in a Helm hook Job** (`templates/migration-job.yaml`),
   `pre-install` and `pre-upgrade`. Helm runs it before it creates or updates
   any regular resource of the release. A failed or timed-out Job fails the
   install or upgrade, so the Deployment is not rolled out on an unmigrated
   schema. There is no `pre-rollback` hook: Flyway has no down migrations, and
   an older image validates against a newer schema history (Flyway ignores
   applied migrations it does not know).
3. **The Job reuses the service image in migrate-only mode.** With the first
   argument `migrate`, `RiskDecisioningApplication` hands over to
   `DatabaseMigration`. That starts only the datasource, Flyway and
   `DatabaseTlsGuard` (on by default, so the Job needs the `DB_SSL_ROOT_CERT`
   the chart sets; profile `db-migrate`, `risk.database.flyway=migrate`),
   migrates as the schema owner and exits: 0 when every migration is applied,
   1 otherwise. It starts no web server, JPA, Kafka, security or outbox relay.
4. **Only the Job sees the owner credential.** The Job gets the `db-migration`
   ExternalSecret, the same verified `DB_URL` (from values, checked by
   `risk.guard`, the same guard call as the Deployment's: the vendored
   cicd-templates `fbx.guard` and this chart's checks), `DB_USERNAME`, the
   `rds-ca-bundle` mount and
   `DB_SSL_ROOT_CERT`. It has the app's pod and container security contexts
   (non-root, read-only root filesystem, all capabilities dropped). The
   `db-migration` ExternalSecret and the Job's own ServiceAccount (no IAM role,
   no API token) are hooks at weight -10, created before the Job, and deleted
   once every hook has succeeded. The owner credential exists in the namespace
   only while a migration runs. The Deployment mounts only the runtime
   credential.
5. **No Istio sidecar on the Job's pods.** The pod template sets
   `sidecar.istio.io/inject: "false"` after `podLabels`, so it overrides them.
   Aurora egress is a Kubernetes NetworkPolicy on the `app.kubernetes.io/name`
   label (service-mesh repository), so the Job needs no proxy; without native
   sidecars an injected proxy would keep the Job from completing.
6. **The service validates as the runtime role.** `risk.database.flyway`
   defaults to `validate` (`FlywayStartupConfiguration`): at startup Flyway
   checks the schema history and the service refuses to start while a
   migration is pending or an applied one differs. V11 grants the runtime role
   `SELECT` only on `flyway_schema_history`, so it can read the history but not
   record, repair or remove a migration. After that validate, `RuntimeRoleGuard`
   (`RuntimeRoleConfiguration`, service context only, never the Job) asserts on
   the service's own pool that `current_user` is not a member of the owner of
   the checked tables nor of the owner of the schema (`pg_has_role`, `MEMBER`),
   owns no table in the schema, has no `CREATE` on it, and has, on
   `risk_assessment` (the decisions of record), no `UPDATE`, `DELETE`,
   `TRUNCATE` or `TRIGGER` and no `UPDATE` or `REFERENCES` on any of its
   columns (`has_any_column_privilege`: a column-level grant is invisible to
   `has_table_privilege`); on the tables V11 lets it only read,
   `legacy_credit_risk_assessment` (written by the backfill role) and
   `flyway_schema_history`, no `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE` or
   `TRIGGER` and no column-level `INSERT`, `UPDATE` or `REFERENCES`.
   `outbox_event` is not checked: V11 grants `UPDATE` and `DELETE` on it on
   purpose (the relay marks, parks and purges rows). Any finding fails the
   context without naming the role, the URL or a credential. The guard is a
   `SmartInitializingSingleton` registered `@Lazy(false)`, so it runs once every
   singleton exists, before the web server, and
   `spring.main.lazy-initialization=true` (a config key the chart refuses)
   cannot skip it; it depends on Flyway's initializer, so the validate still
   runs first. It is on by default like the TLS assertions and off only with
   the `local` profile (a single-user local run is its own owner); the test
   switch `fintechbankx.tls.enforce=false` leaves it on, and the integration
   tests run it as the least-privilege runtime role. Tests keep `migrate` as
   the owner (`PostgresTestDatabase`). `DatabaseMigrationIT` covers the Job and
   the service (a role granted the owner, a role with a stray `UPDATE`, lazy
   initialization included), `RuntimeRoleGuardIT` each privilege with a scratch
   role. Any local, ephemeral or CI environment therefore runs the migrate
   step before the application: `java -jar risk-decisioning-service.jar migrate`
   or `./gradlew :risk-bootstrap:bootRun --args=migrate`.
7. **`externalSecret.migrationSecretName` is required, is not the runtime
   secret, and must be under `<env>/risk-decisioning-service/`.** The chart
   no longer renders without it, so the service can no longer migrate
   silently as the runtime role; it also refuses the same Secrets Manager
   name for `migrationSecretName` and `remoteSecretName` (compared trimmed
   and without a trailing `/`). The single-user fallback is refused in the
   image too: `MigrationRoleGuard` (a `BeanFactoryPostProcessor`, before any
   connection) fails the context when both `DB_MIGRATION_USERNAME` and
   `DB_USERNAME` are known and name the same role, case-insensitively, so a
   `db-migration` secret that holds the runtime credential exits the Job
   with 1 (`DatabaseMigrationIT`, `MigrationRoleGuardTest`). That guard
   compares names only; the privileges behind the runtime role's name are
   re-checked at every service start by `RuntimeRoleGuard` (item 6).
8. CI (`deploy/helm`, `scripts/ci/check-migration-job.py`) asserts the split.
   The app pods never reference the `db-migration` secret. The Job exists with
   the hooks, limits, labels and sidecar opt-out above. No Service, PDB,
   NetworkPolicy, Deployment or topology-spread selector matches its pods.
   kubeconform `-strict`, the database CA check (Deployment and Job) and the
   ExternalSecret naming check pass. `deploy/terraform` asserts the
   `db-migration` secret.

## Consequences

- The schema owner's credential leaves the service pods. Changing a decision
  of record now needs the owner credential from Secrets Manager or the
  database's master user, not a pod's environment. A DBA can still change
  rows; tamper evidence (for example a hash chain) is not built, and unlike
  compliance there is no insert-only trigger: the runtime role's grants alone
  make `risk_assessment` insert-only, which is why the service asserts those
  grants itself at every start (`RuntimeRoleGuard`) instead of trusting the
  bootstrap: a pod on an over-granted or owner-member role never serves.
- Deploy order: the Job runs and must succeed before any new pod starts. Helm's
  `--timeout` must exceed the Job's `activeDeadlineSeconds` (600 s); use
  `--timeout 15m`.
- An empty database no longer starts the application: every start path runs
  the migrate step first (README, runbook).
- The Job's pods match the mesh's `allow-egress-aurora` policy (it selects
  `app.kubernetes.io/name`). They have no IAM role and no sidecar, so MSK
  refuses them and they are outside the mesh.
- A pending migration keeps new service pods from starting
  (`FlywayValidateException`). With `maxUnavailable: 0` the old pods keep
  serving, and the rollout stalls instead of serving on the wrong schema.
- Not verified on a cluster: that External Secrets syncs the hook
  ExternalSecret before the Job's pod gives up, and the timings. The chart is
  checked only by rendering, kubeconform and the mutation checks.

## Mesh contract (pending)

The db-migration Job is not yet in the mesh contract
(`fintechbankx-platform-mesh-security-service-mesh`,
`contracts/mesh-contract.yaml`). It relies on the name-keyed Aurora
NetworkPolicy (`allow-egress-aurora` selects `app.kubernetes.io/name`, which
the Job's pods carry as `risk-decisioning-service`) and on the namespace DNS
policy. On 2026-10-10 the CRC thread asked the mesh squad, via the project
coordinator, to add the workload to the contract: ServiceAccount
`risk-decisioning-service-db-migration`, selector
`app.kubernetes.io/component=db-migration`, no sidecar, egress to Aurora and
DNS only; and to confirm that an un-injected pod in an `istio-injection`
namespace is acceptable (it is outside the mesh, so STRICT mTLS and the
ALLOW rules do not apply to it). Status: pending the mesh answer. Until then
the Job depends on the two policies above; a mesh change that keys Aurora
egress on the component label or on a sidecar identity would stop it.

## Reversibility

Reversible. Moving Flyway back into the pods means setting
`risk.database.flyway=migrate` in the service and mounting the secret again.
That would undo the security property, so it needs a new decision that
supersedes this one. V11's grants are additive and can be revoked by a later
migration.
