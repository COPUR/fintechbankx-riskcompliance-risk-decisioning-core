# Deployment and AWS Well-Architected mapping

How `svc-rsk-decisioning` runs on AWS, and which file implements each
Well-Architected concern. Claims here point at code; anything not listed is
not done yet.

## Runtime shape

```
payment services ─HTTP─▶ risk-decisioning-service pods (EKS namespace risk, 3..12, HPA)
                            ├─ JDBC ─▶ Aurora PostgreSQL Serverless v2 (Multi-AZ)
                            └─ outbox relay ─▶ Amazon MSK (IAM auth) evt.rsk.risk.assessed.v1
```

| Artifact | Path |
|---|---|
| Image | `Dockerfile` (layered Spring Boot jar, JRE 23, uid 10001) |
| Kubernetes | `deploy/helm/risk-decisioning-service` (`values.yaml` prod-shaped, `values-dev.yaml`) |
| AWS | `deploy/terraform` (Aurora, KMS, Secrets Manager, IRSA, alarms; platform `microservice-base` module) |
| Runtime config | `risk-bootstrap/src/main/resources/application.yml` (all environment values from env) |
| CI proof | `.github/workflows/deployability.yml` |

## Well-Architected pillars

| Pillar | What is in place | Where |
|---|---|---|
| Operational excellence | Health groups for startup/liveness/readiness on a separate management port; Prometheus metrics with `service` tag; outbox gauges `outbox_pending_events`, `outbox_oldest_pending_age_seconds` and `outbox_parked_rows`, counters `outbox_send_failures_total` and `outbox_parked_events_total`; correlation id (`x-fapi-interaction-id`) in logs, responses and events; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server: issuer and audience (`svc-rsk-decisioning`) validated, Keycloak realm roles and method security (`BANKER`, `ADMIN`, or `SERVICE` from a client on `SERVICE_CALLERS`); mesh-wide STRICT mTLS with default-deny and ALLOW rules owned by the service-mesh repo, namespace network policies also come from the service-mesh repo (mesh #11 admits `payments` to namespace `risk`), and the chart ships none; DPoP not required for internal client-credentials calls (platform contract); non-root, read-only root filesystem, all capabilities dropped; DB credential from Secrets Manager via External Secrets; KMS-encrypted storage, snapshots, logs and secrets; TLS enforced (`rds.force_ssl`); IRSA least privilege (MSK access only to `evt.rsk.risk.*` topics, IAM auth instead of shared Kafka credentials); DB reachable only from the workload security group; events carry ids, decision and reason codes only | `SecurityConfiguration`, `ServiceCallerPolicy`, `RiskController`, `deployment.yaml`, `externalsecret.yaml`, `main.tf` |
| Reliability | Aurora Multi-AZ, PITR, deletion protection; pods spread across zones, PDB, graceful shutdown; decisions are insert-only and unique per transaction, so retries are safe and return the original decision; transactional outbox (no lost or phantom events), ordered single-relay publishing, idempotent Kafka producer | `main.tf`, `deployment.yaml`, `pdb.yaml`, `RiskAssessmentService`, `TransactionalRiskAssessmentUseCase`, `OutboxRelay`, `V1__create_risk_assessment.sql`, `V3__create_outbox.sql` |
| Performance efficiency | Stateless pods scaled by HPA; Aurora Serverless v2; virtual threads; unique index for the transaction lookup, a partial index for the manual-review queue and one for the outbox queue | `hpa.yaml`, `main.tf`, `application.yml`, `V1__create_risk_assessment.sql`, `V3__create_outbox.sql` |
| Cost optimization | Serverless v2 floor of 0.5 ACU in dev; dev overrides (single Aurora instance, 2-4 pods); log retention 30 days outside prod; outbox rows purged after 7 days | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished` |
| Sustainability | Scale-down to the minimum footprint outside peak; layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Preconditions for turning on the outbox relay

The chart ships `config.OUTBOX_RELAY_ENABLED: "false"`. The mesh is default-deny for egress per namespace, so the
relay's connection to MSK (port 9098) is dropped until the platform allows it. Before setting
`--set config.OUTBOX_RELAY_ENABLED=true` (runbook step 4):

1. The mesh contract lists `msk` for `risk-decisioning-service` (allow-egress-msk generated for namespace `risk`);
   owned by fintechbankx-platform-mesh-security-service-mesh.
2. asyncapi-catalog #11 (the catalog entry for this contract) is merged, and topic `evt.rsk.risk.assessed.v1`
   exists on the platform cluster.
3. `msk_cluster_arn` is set in Terraform, so the IRSA role can publish to `evt.rsk.risk.*`.

## Known gaps

- The mesh contract does not yet give namespace `risk` MSK egress, so the relay stays off (see above).

- No caller uses the service yet; payments still screen locally.
- Topic `evt.rsk.risk.assessed.v1` and its ACLs are not yet created on the platform MSK cluster; the IRSA Kafka policy is only attached when `msk_cluster_arn` is set.
- The application DB role (`risk_decisioning_app`) is created by a DBA bootstrap step, not by Terraform.
- `microservice-base` is referenced at `ref=main`; pin a tag once the modules repo publishes releases.
- No load test yet; HPA targets are starting values.
