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
| Operational excellence | Health groups for startup/liveness/readiness on a separate management port; Prometheus metrics with `service` tag; outbox backlog gauge `outbox_pending_events`; correlation id (`x-fapi-interaction-id`) in logs, responses and events; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server: issuer and audience (`svc-rsk-decisioning`) validated, Keycloak realm roles and method security (`BANKER`, `ADMIN`, or `SERVICE` from a client on `SERVICE_CALLERS`); Istio STRICT mTLS, AuthorizationPolicy and NetworkPolicy allow only the payments caller and the observability namespace; non-root, read-only root filesystem, all capabilities dropped; DB credential from Secrets Manager via External Secrets; KMS-encrypted storage, snapshots, logs and secrets; TLS enforced (`rds.force_ssl`); IRSA least privilege (MSK access only to `evt.rsk.risk.*` topics, IAM auth instead of shared Kafka credentials); DB reachable only from the workload security group; events carry ids, decision and reason codes only | `SecurityConfiguration`, `ServiceCallerPolicy`, `RiskController`, `mesh-policies.yaml`, `deployment.yaml`, `externalsecret.yaml`, `main.tf` |
| Reliability | Aurora Multi-AZ, PITR, deletion protection; pods spread across zones, PDB, graceful shutdown; decisions are insert-only and unique per transaction, so retries are safe and return the original decision; transactional outbox (no lost or phantom events), ordered single-relay publishing, idempotent Kafka producer | `main.tf`, `deployment.yaml`, `pdb.yaml`, `RiskAssessmentService`, `TransactionalRiskAssessmentUseCase`, `OutboxRelay`, `V1__create_risk_assessment.sql`, `V3__create_outbox.sql` |
| Performance efficiency | Stateless pods scaled by HPA; Aurora Serverless v2; virtual threads; unique index for the transaction lookup, a partial index for the manual-review queue and one for the outbox queue | `hpa.yaml`, `main.tf`, `application.yml`, `V1__create_risk_assessment.sql`, `V3__create_outbox.sql` |
| Cost optimization | Serverless v2 floor of 0.5 ACU in dev; dev overrides (single Aurora instance, 2-4 pods); log retention 30 days outside prod; outbox rows purged after 7 days | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished` |
| Sustainability | Scale-down to the minimum footprint outside peak; layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Known gaps

- No caller uses the service yet; payments still screen locally.
- Topic `evt.rsk.risk.assessed.v1` and its ACLs are not yet created on the platform MSK cluster; the IRSA Kafka policy is only attached when `msk_cluster_arn` is set.
- The application DB role (`risk_decisioning_app`) is created by a DBA bootstrap step, not by Terraform.
- `microservice-base` is referenced at `ref=main`; pin a tag once the modules repo publishes releases.
- No load test yet; HPA targets are starting values.
