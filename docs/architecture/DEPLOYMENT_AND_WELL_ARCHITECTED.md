# Deployment and AWS Well-Architected mapping

How `svc-rsk-decisioning` runs on AWS, and which file implements each
Well-Architected concern. Claims here point at code; anything not listed is
not done yet.

## Runtime shape

```
helm install/upgrade ─▶ pre-install/pre-upgrade Job risk-decisioning-service-db-migration (no sidecar)
                            └─ JDBC (schema owner, Flyway migrate) ─▶ Aurora; must succeed before any pod rolls out
payment services ─HTTP─▶ risk-decisioning-service pods (EKS namespace risk, 3..12, HPA)
                            ├─ JDBC (runtime role, Flyway validate at startup) ─▶ Aurora PostgreSQL Serverless v2 (Multi-AZ)
                            └─ outbox relay ─▶ Amazon MSK (IAM auth) evt.rsk.risk.v1
```

| Artifact | Path |
|---|---|
| Image | `Dockerfile` (layered Spring Boot jar, JRE 23, uid 10001) |
| Kubernetes | `deploy/helm/risk-decisioning-service` (`values.yaml` prod-shaped, `values-dev.yaml`; Flyway in the hook Job `templates/migration-job.yaml`, [decision 0002](decisions/0002-flyway-runs-in-a-migration-job.md)) |
| AWS | `deploy/terraform` (Aurora, KMS, Secrets Manager, IRSA, alarms; platform `microservice-base` module) |
| Runtime config | `risk-bootstrap/src/main/resources/application.yml` (all environment values from env) |
| CI proof | `.github/workflows/deployability.yml` |

## Well-Architected pillars

| Pillar | What is in place | Where |
|---|---|---|
| Operational excellence | Health groups for startup/liveness/readiness on a separate management port; Prometheus metrics with `service` tag; outbox gauges `outbox_pending_events`, `outbox_oldest_pending_age_seconds` and `outbox_parked_rows`, counters `outbox_send_failures_total` and `outbox_parked_events_total`; correlation id (`x-fapi-interaction-id`) in logs, responses and events; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server: issuer and audience (`svc-rsk-decisioning`) validated, Keycloak realm roles and method security (`BANKER`, `ADMIN`, or `SERVICE` from a client on `SERVICE_CALLERS`); mesh-wide STRICT mTLS with default-deny and ALLOW rules owned by the service-mesh repo, namespace network policies also come from the service-mesh repo (mesh #11 admits `payments` to namespace `risk`), and the chart ships none; DPoP not required for internal client-credentials calls (platform contract); non-root, read-only root filesystem, all capabilities dropped (the migration Job too); the service pods hold only the runtime role's DB credential (V11: insert and read decisions, work the outbox, read the schema history) and validate the schema at startup; the schema owner's credential is mounted only by the Helm pre-install/pre-upgrade migration Job (own ServiceAccount without IAM role, no Istio sidecar) and exists in the namespace only while it runs (decision 0002, `check-migration-job.py`); on AWS with the relay on, `KafkaTlsGuard` refuses a producer that is not SASL_SSL; DB credentials from Secrets Manager via External Secrets; two KMS keys (ADR-023): an untagged database key for Aurora storage, snapshots and Performance Insights, and a secrets key tagged `fintechbankx.io/secrets=true` for Secrets Manager only, so External Secrets cannot decrypt the database key; TLS enforced (`rds.force_ssl`); IRSA least privilege (MSK writes only to the aggregate topic `evt.rsk.risk.v1`, IAM auth instead of shared Kafka credentials; `deploy/terraform/tests/msk-topic.tftest.hcl`); DB reachable only from the workload security group; events carry ids, decision and reason codes only | `SecurityConfiguration`, `ServiceCallerPolicy`, `RiskController`, `deployment.yaml`, `migration-job.yaml`, `externalsecret.yaml`, `DatabaseMigration`, `FlywayStartupConfiguration`, `KafkaTlsGuard`, `main.tf` |
| Reliability | Aurora Multi-AZ, PITR, deletion protection; schema migrated by a hook Job before the rollout (a failed Job fails the release and leaves the running pods alone; new pods refuse to start on a pending migration); pods spread across zones, PDB, graceful shutdown; decisions are insert-only and unique per transaction, so retries are safe and return the original decision; transactional outbox (no lost or phantom events), ordered single-relay publishing, idempotent Kafka producer | `main.tf`, `deployment.yaml`, `pdb.yaml`, `RiskAssessmentService`, `TransactionalRiskAssessmentUseCase`, `OutboxRelay`, `V1__create_risk_assessment.sql`, `V3__create_outbox.sql` |
| Performance efficiency | Stateless pods scaled by HPA; Aurora Serverless v2; virtual threads; unique index for the transaction lookup, a partial index for the manual-review queue and one for the outbox queue | `hpa.yaml`, `main.tf`, `application.yml`, `V1__create_risk_assessment.sql`, `V3__create_outbox.sql` |
| Cost optimization | Serverless v2 floor of 0.5 ACU in dev; dev overrides (single Aurora instance, 2-4 pods); log retention 30 days outside prod; outbox rows purged after 7 days | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished` |
| Sustainability | Scale-down to the minimum footprint outside peak; layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Preconditions for turning on the outbox relay

The chart ships `config.OUTBOX_RELAY_ENABLED: "false"`. The mesh is default-deny for egress per namespace, so the
relay's connection to MSK (port 9098) is dropped until the platform allows it. Before setting
`--set config.OUTBOX_RELAY_ENABLED=true` (runbook step 4):

1. The mesh contract lists `msk` for `risk-decisioning-service` (allow-egress-msk generated for namespace `risk`);
   owned by fintechbankx-platform-mesh-security-service-mesh.
2. asyncapi-catalog #11 (the catalog entry for this contract) is merged, and the aggregate topic `evt.rsk.risk.v1`
   exists on the platform cluster (ADR-019: one topic per aggregate; the per-event topic is retired).
3. `msk_cluster_arn` is set in Terraform, so the IRSA role can publish to `evt.rsk.risk.v1`.

Every record on `evt.rsk.risk.v1` is keyed by the assessment id and carries the `eventType`, `eventId` and
`correlationId` headers (plus `traceparent` when traced). Consumers route on `eventType` and skip types they do not
handle, so adding an event type is additive; a breaking change to one event is a new eventType `...v2` on the same
topic, and the topic major changes only for key, partition-count or cleanup changes.

Aurora TLS: `config.DB_URL` must use `sslmode=verify-full` (the Terraform `jdbc_url` output does, with
`sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem`); the chart fails to render otherwise. It mounts ConfigMap
`rds-ca-bundle` read-only at `/etc/fintechbankx/rds-ca` (not optional) and exports `DB_SSL_ROOT_CERT`
(cicd-templates 4f0f266), always: there is no switch to turn it off, as in compliance. Local, compose and test
database URLs are unchanged.

## Local and ephemeral runs

The service only validates the schema, so an empty database does not start it. Every local, ephemeral or CI start
path runs the migrate step first: `./gradlew :risk-bootstrap:bootRun --args=migrate` (or
`java -jar risk-decisioning-service.jar migrate`), then the application without the argument. In the cluster the
chart's hook Job does this before any pod starts (decision 0002). The backfill rehearsal (`deploy/data-split-rehearsal`)
applies the migrations with psql and never boots the jar. This repository has no compose file.

## Known gaps

- The mesh contract does not yet give namespace `risk` MSK egress, so the relay stays off (see above).

- No caller uses the service yet; payments still screen locally.
- Topic `evt.rsk.risk.v1` and its ACLs are not yet created on the platform MSK cluster; the IRSA Kafka policy is only attached when `msk_cluster_arn` is set.
- The DB roles (owner `risk_decisioning_owner`, runtime `risk_decisioning_app`) are created by a DBA bootstrap step, not by Terraform; Terraform creates their secrets (`db-migration`, `db-app`).
- The migration Job is checked by rendering, kubeconform and CI mutation checks only; it has not run on a cluster yet (External Secrets sync timing).
- `microservice-base` is referenced at `ref=main`; pin a tag once the modules repo publishes releases.
- No load test yet; HPA targets are starting values.
