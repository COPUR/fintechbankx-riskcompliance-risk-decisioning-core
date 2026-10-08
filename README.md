# fintechbankx-riskcompliance-risk-decisioning-core

Bu repository, FinTechBankX DDD/EDA dönüşümünde **svc-rsk-decisioning** servis yetkinliğinin kaynak kodunu, kontratlarını ve operasyonel guardrail'lerini içerir.

## Sorumluluk ve Sahiplik
| Alan | Değer |
|---|---|
| Organizasyon Modeli | Spotify Model (Tribe/Squad) |
| Tribe | Lending and Money Movement Tribe |
| Squad | Risk and Compliance Decisioning Squad |
| Repo Kümesi (Capability) | risk |
| Service ID | svc-rsk-decisioning |
| Bounded Context | risk_decisioning |
| Wave | 4 |
| Mimari Yaklaşım | DDD + Hexagonal + Event-Driven |

## Sorumluluk Sınırları
- Bu repo kendi bounded context domain modelinin tek yetkili sahibidir.
- Domain kuralları altyapıdan bağımsız tutulur; entegrasyonlar port/adapter katmanında yönetilir.
- API/Event kontratları geriye dönük uyumluluk kontrolleri ile korunur.
- Güvenlik guardrail'leri (mTLS, token doğrulama, idempotency, log hijyeni) CI/CD ile zorlanır.

## Kapsam
### In Scope
- risk_decisioning bağlamına ait uygulama kodu, testler ve otomasyon.
- Bu servise ait OpenAPI/AsyncAPI veya şema artefaktları.
- Bu servisin çalışma zamanı operasyonları (gözlemlenebilirlik, release, rollback).

### Out of Scope
- Diğer bounded context'lerin iş kuralları ve veri sahipliği.
- Paylaşımlı DB anti-pattern'i; cross-context doğrudan tablo erişimi.
- Platform dışı gizli bilgi/anahtar yönetimi (merkezi policy dışında local hardcode).

## Mühendislik Standartları
- **TDD öncelikli** geliştirme, birim test + entegrasyon testi.
- **Clean Architecture**: Domain katmanı framework bağımsız.
- **12-Factor** ve environment-driven configuration.
- **FAPI odaklı güvenlik** (OIDC/OAuth2, mTLS, DPoP gereksinimleri ilgili servislerde).
- **PII güvenliği**: loglarda maskeleme, secret'ların source/env içine yazılmaması.

## Branching ve Release Akışı
- Uzun ömürlü branch'ler: `main`, `dev`, `staging`, `local`.
- Feature branch kuralı: `codex/<kisa-aciklama>`.
- Release yaklaşımı: PR + required status checks + tag tabanlı sürümleme.

## Run, test and deploy

| What | Command / path |
|---|---|
| Unit and integration tests | `./gradlew test` (integration tests need `TEST_DB_URL` or Docker) |
| Run locally | `SPRING_DATASOURCE_PASSWORD=... ./gradlew :risk-bootstrap:bootRun` |
| Database migrations | `risk-infrastructure/src/main/resources/db/migration` (schema `sc_rsk_decisioning`) |
| API contract | [api/openapi/risk-context.yaml](api/openapi/risk-context.yaml) |
| Event contract | [api/asyncapi/svc-rsk-decisioning.yaml](api/asyncapi/svc-rsk-decisioning.yaml) (validate: `npx -y @asyncapi/cli@2.13.0 validate api/asyncapi/svc-rsk-decisioning.yaml`) |
| Container image | `docker build -t risk-decisioning-service .` |
| Kubernetes | `deploy/helm/risk-decisioning-service` |
| AWS infrastructure | `deploy/terraform` |
| Data split from the monolith | [RUNBOOK-EXTRACT-rsk-decisioning](docs/migration/RUNBOOK-EXTRACT-rsk-decisioning.md) |
| Decisions | [ADR 0001: risk facts are caller-attested](docs/architecture/decisions/0001-risk-facts-are-caller-attested.md) (Proposed) |
| Deployment and Well-Architected mapping | [DEPLOYMENT_AND_WELL_ARCHITECTED](docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md) |

## Calling the service

- `transactionId` is unique across **all** callers: the first assessment of an id is the decision of record for everyone. Callers must namespace their ids, for example `PAY-<payment id>`.
- A retry is answered with the stored decision only when it repeats every input: amount (compared by value), currency, `highRiskCountry` and `velocityScore`. Any other input under the same id is `409 TRANSACTION_ALREADY_ASSESSED`.
- `409 DUPLICATE_REQUEST` means a concurrent first request for the same id won. Retry to get its decision.
- Inputs are refused with `400 INVALID_REQUEST` rather than rounded or truncated:
  - `transactionId` longer than 128 characters;
  - currency that is not an upper-case ISO 4217 code;
  - amount with more than 15 integer digits or more than 4 decimals;
  - `velocityScore` outside 0..100.
- Tokens must carry `svc-rsk-decisioning` in `aud` (`OIDC_AUDIENCE`).
  - Bank staff need `BANKER` or `ADMIN`.
  - Services need `SERVICE` and a client id (`azp`) listed in `SERVICE_CALLERS` (default `svc-pay-initiation-settlement`).
  - DPoP is not required for internal client-credentials calls, which are bound by mesh mTLS (platform contract).

Module layout: `risk-domain` (assessment, events, policy, ports) ← `risk-application` (use case) ← `risk-infrastructure` (JPA, transactional outbox, web, security) ← `risk-bootstrap` (Spring Boot app).

## Published events

| Topic | eventType | Key | When |
|---|---|---|---|
| `evt.rsk.risk.assessed.v1` | `Risk.RiskAssessment.Assessed.v1` | assessment id (`aggregateId`) | A transaction gets its first decision of record. A retry that returns the stored decision publishes nothing. |

- Written to `sc_rsk_decisioning.outbox_event` in the same transaction as the `risk_assessment` row (`TransactionalRiskAssessmentUseCase`, `OutboxRiskEventPublisher`). If the insert loses a race on the unique `transaction_id`, the transaction rolls back and no event is left behind.
- `OutboxRelay` publishes rows in insertion order. One replica relays at a time (Postgres advisory lock). Delivery is at least once, so consumers de-duplicate on `eventId`. Published rows are purged after `risk.outbox.retention` (7 days).
- Runtime settings: `KAFKA_BOOTSTRAP_SERVERS` and `KAFKA_SECURITY_PROTOCOL`; `SPRING_PROFILES_ACTIVE=kafka-msk` selects Amazon MSK with IAM auth through the IRSA role, and `kafka-strimzi` selects mutual TLS with PEM from `KAFKA_TLS_CERT`/`KAFKA_TLS_KEY`/`KAFKA_TLS_CA`; `OUTBOX_RELAY_ENABLED` is `"false"` in the Helm chart until the mesh contract gives namespace `risk` MSK egress (runbook step 4; the application default for local runs is `true`). The producer follows the platform client guide: client id `svc-rsk-decisioning`, `acks=all`, idempotent, lz4, `linger.ms=5`, and no topic auto-creation.
- Record headers: `eventType`, `eventId`, `correlationId`, `x-fapi-interaction-id`, and a W3C `traceparent` when the request that raised the event was traced.
- Backlog metric: `outbox_pending_events{service="svc-rsk-decisioning"}`. Alert on `outbox_oldest_pending_age_seconds`. Per ADR-021 decision 4, a payload error (record too large, not serializable, invalid topic) parks the row (`outbox_parked_events{service="svc-rsk-decisioning"}`) and the relay continues; every other failure (retriable, authorization, SASL/IAM, unclassified) stops the batch without marking the row and is retried with exponential backoff (up to `risk.outbox.relay.max-backoff`, 5 min), never parked. Manual park and replay steps are in the runbook.
- Status: Proposed. The catalog entry for this contract is proposed in fintechbankx-governance-architecture-enablement-asyncapi-catalog PR #11 (not merged), and the topics are not yet created on the platform cluster.

## Dokümantasyon ve Referanslar
- [Enterprise Architecture Hub](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture)
- [Secure Microservices Architecture](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/architecture/overview/SECURE_MICROSERVICES_ARCHITECTURE.md)
- [Service Data Ownership Matrix](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_DATA_OWNERSHIP_MATRIX.md)
- [Service API Contracts Index](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_API_CONTRACTS_INDEX.md)
- [Transformation Plan](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/MICROSERVICES_TRANSFORMATION_PLAN.md)
- [Capability Map (PUML)](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/puml/service-mesh/enterprise-capability-map.puml)
- [Bu Repo Dokümantasyonu](./docs)

## Güvenlik ve Uyumluluk Notları
- Gerçek secret değerleri repo veya `.env` içinde tutulmaz.
- Secret üretim/rotasyon olayları merkezi log/SIEM'e taşınır.
- CI pipeline, anonimlik ve local-path sızıntısı kontrollerini bloklayıcı olarak çalıştırır.

## Katkı
- Katkı süreci için `CONTRIBUTING.md` ve squad runbook'ları izlenmelidir.
- PR'larda mimari kararlar ADR veya backlog referansı ile ilişkilendirilmelidir.

<!-- cell-architecture-start -->
## Cell-Based Architecture

This repository participates in the FinTechBankX cell-based resilience program.

- Plan: docs/architecture/CELL_BASED_ARCHITECTURE_IMPLEMENTATION_PLAN.md
- Backlog: docs/project-management/CELL_ARCHITECTURE_BACKLOG_BOARD.md
<!-- cell-architecture-end -->
