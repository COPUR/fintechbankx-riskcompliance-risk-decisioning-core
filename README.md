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
| Run locally | With the `local` profile, migrate first, then start, with the database password exported as `SPRING_DATASOURCE_PASSWORD`, run `SPRING_PROFILES_ACTIVE=local ./gradlew :risk-bootstrap:bootRun --args=migrate` once per new migration (or `java -jar risk-bootstrap/build/libs/risk-decisioning-service.jar migrate`, same profile), then the same command without `--args`. The TLS startup assertions (`DatabaseTlsGuard`, `KafkaTlsGuard`) are on by default and require the RDS CA bundle (`DB_SSL_ROOT_CERT`), so without the profile the service does not start; only the `local` profile and the test switch `fintechbankx.tls.enforce=false` (test code only) turn them off, and the log then says so at WARN. The service only validates the schema and refuses to start on an empty or unmigrated database ([decision 0002](docs/architecture/decisions/0002-flyway-runs-in-a-migration-job.md)); the relay is off by default (`OUTBOX_RELAY_ENABLED=true` with Kafka on `localhost:9092` to publish) |
| Database migrations | `risk-infrastructure/src/main/resources/db/migration` (schema `sc_rsk_decisioning`), applied by the chart's pre-install/pre-upgrade Job as the schema owner, never by the service pods; the pods connect as the runtime role V11 and V12 grant |
| High-risk country list | `scripts/ops/load-high-risk-countries.sh <conninfo> <codes file> <list source> <list version>` as the schema owner, after every FATF plenary; rehearsed by `scripts/migration/verify-high-risk-country-load.sh` (`deploy/data-split-rehearsal`) |
| API contract | [api/openapi/risk-context.yaml](api/openapi/risk-context.yaml) |
| Event contract | [api/asyncapi/svc-rsk-decisioning.yaml](api/asyncapi/svc-rsk-decisioning.yaml) (validate: `npx -y @asyncapi/cli@2.13.0 validate api/asyncapi/svc-rsk-decisioning.yaml`). Breaking-change gate in `ci/test` (ADR-019 section 5): `npm ci --prefix scripts/ci/asyncapi && ASYNCAPI_DIR=api/asyncapi node scripts/ci/asyncapi/asyncapi-breaking.mjs` compares with `origin/main`. `asyncapi-breaking.mjs` and `lib/` are copied unchanged from the asyncapi catalog (44837cc). The shared CI template carries the gate in cicd-templates #11 (6bd961a, `publishes-events: true`); this hand-rolled step stays until #11 merges |
| Container image | `docker build -t risk-decisioning-service .` |
| Kubernetes | `deploy/helm/risk-decisioning-service` (required config: `DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `OIDC_*`, `SERVICE_CALLERS_ELIGIBILITY`, `RISK_HOME_COUNTRY`, `CUSTOMER_SERVICE_URL`; `externalSecret.oidcClientSecretName` (`<env>/risk-decisioning-service/oidc-client`) feeds the chart's own ExternalSecret that renders `CUSTOMER_CLIENT_CREDENTIAL` and `CUSTOMER_TOKEN_URI`; `scripts/ci/chart-render-probes.sh` has the refusals) |
| AWS infrastructure | `deploy/terraform` |
| Data split from the monolith | [RUNBOOK-EXTRACT-rsk-decisioning](docs/migration/RUNBOOK-EXTRACT-rsk-decisioning.md) |
| Decisions | [ADR 0001: risk facts are caller-attested](docs/architecture/decisions/0001-risk-facts-are-caller-attested.md), [ADR 0002: Flyway runs in a migration Job](docs/architecture/decisions/0002-flyway-runs-in-a-migration-job.md) (both Proposed) |
| Deployment and Well-Architected mapping | [DEPLOYMENT_AND_WELL_ARCHITECTED](docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md) |

## Calling the service

Screening facts are computed by their owner (plan 2026-10-18): risk owns the high-risk country and the velocity, payments send ids and amounts, risk decides loan eligibility.

- `POST /api/v2/risk/assess` (body `transactionId, customerId, amount, currency, paymentType, counterpartyAccount`) is the operation payments call. Risk resolves the counterparty's country from `counterpartyAccount` (an IBAN of a country in the domain's IBAN-registry allow-list, at that country's length, after its mod-97 check: ZZ or QQ with valid check digits is unknown; an internal `ACC-` account is `RISK_HOME_COUNTRY`; anything else is unknown and counts as high risk), checks it against the risk-owned table `sc_rsk_decisioning.high_risk_country` (V12, loaded from the FATF list with `scripts/ops/load-high-risk-countries.sh <conninfo> <codes file> <list source> <list version>`; transactional replace, refuses a password in the conninfo and an empty file), and computes the velocity from its own decisions of record for `customerId` in the trailing `RISK_VELOCITY_WINDOW` (default `PT24H`): 10 points per prior assessment, capped at 100, on the policy's existing scale (4 prior -> `MEDIUM_VELOCITY`, 7 prior -> `HIGH_VELOCITY`; `VelocityPolicy`). The decision stores both facts, the customer and the counterparty country (V13) with `attestationSource` `RISK_RESOLVED`. If the list is empty or its `loaded_at` is older than `RISK_COUNTRY_LIST_MAX_AGE` (default `P35D`), the answer is `503 RISK_SOURCE_UNAVAILABLE` and nothing is decided.
- `POST /api/v1/risk/assess` (caller-stated `highRiskCountry` and `velocityScore`) is deprecated. It answers only while `RISK_V1_CALLER_FACTS_ENABLED=true`, the default (so this release deploys before payments moves to v2 without an outage); once payments is on v2 the operator sets it to `false` (runbook cutover step 2) and it is `410 API_VERSION_GONE` with a body naming v2. `GET /api/v1/risk/assessments/{transactionId}` is not deprecated.
- `POST /api/v1/risk/loan-eligibility` (body `customerId, requestedAmount, currency`): risk reads the customer's credit standing (`customerId, creditScore` optional`, creditLimit, usedCredit, availableCredit, currency`) from `GET /api/v1/customers/{id}/credit-standing` (client-credentials token for `svc-rsk-decisioning`, `CUSTOMER_SERVICE_URL`, `CUSTOMER_CLIENT_CREDENTIAL`) and applies the rule ported from the customer context (`LoanEligibilityPolicy`: score at least 600, amount within the available credit; parity tests cite `Customer.java` and `CustomerTest.java`). Answers `ELIGIBLE` or `INELIGIBLE` with a reason code; `503 CUSTOMER_SERVICE_UNAVAILABLE` when the customer service cannot be read, `404 CUSTOMER_NOT_FOUND` for an unknown customer. Callers: SERVICE clients on `SERVICE_CALLERS_ELIGIBILITY` (default `svc-ln-loan-lifecycle`) only, no staff role; every decision is logged with the caller's client and the customer id, never the score. A customer 404 counts as "no such customer" only with the customer context's own `CUSTOMER_NOT_FOUND` body (`application/json`); any other 404, or a body naming another customer, is a 503. `creditScore` is optional on the credit response (absent for a customer without one): then the answer is `INELIGIBLE` with `NO_CREDIT_SCORE`, as the customer aggregate's `isEligibleForLoan` (`Customer.java:410-413`) requires a score. The credit-standing read is a customer endpoint added for risk (deploy-order dependency: the customer patch that adds it goes first); its callers are SERVICE tokens with `aud svc-cus-profile-kyc` and an `azp` on customer's `SERVICE_CALLERS_CREDIT_STANDING` (default `svc-rsk-decisioning`).
- `transactionId` is unique across **all** callers: the first assessment of an id is the decision of record for everyone. Callers must namespace their ids, for example `PAY-<payment id>`.
- A v2 retry is answered with the stored decision only when it repeats the caller's inputs: `customerId`, amount (compared by value), currency, `paymentType`, and a `counterpartyAccount` resolving to the same country; the resolved facts are not compared (velocity moves with time). A v1 retry must repeat every input: amount (compared by value), currency, `highRiskCountry`, `velocityScore` and `paymentType`. Any other input under the same id is `409 TRANSACTION_ALREADY_ASSESSED`.
- `409 DUPLICATE_REQUEST` means a concurrent first request for the same id won. Retry to get its decision.
- Inputs are refused with `400 INVALID_REQUEST` rather than rounded or truncated:
  - `transactionId` longer than 128 characters;
  - currency that is not an upper-case ISO 4217 code;
  - amount with more than 15 integer digits or more than 4 decimals;
  - `velocityScore` outside 0..100;
  - `paymentType` omitted, null or not one of `TRANSFER`, `WIRE_TRANSFER`, `ACH`, `CHECK`, `CREDIT_CARD`, `DEBIT_CARD`, `MOBILE_PAYMENT`, `LOAN_PAYMENT`, `BILL_PAYMENT` (the monolith's PaymentType values).
- Policy `rsk-policy-v4` keeps v3's thresholds and patterns and applies them to risk-resolved facts on v2. v3 restored the monolith's suspicious patterns as `BLOCK` (USD only): a round amount (a multiple of 1000 above 10000, `SUSPICIOUS_ROUND_AMOUNT`) and a `MOBILE_PAYMENT` above 5000 (`SUSPICIOUS_MOBILE_AMOUNT`). See ADR 0001.
- Tokens must carry `svc-rsk-decisioning` in `aud` (`OIDC_AUDIENCE`).
  - Bank staff need `BANKER` or `ADMIN`.
  - Services need `SERVICE` and a client id (`azp`) listed in `SERVICE_CALLERS` (default `svc-pay-initiation-settlement`) for decisions, or in `SERVICE_CALLERS_ELIGIBILITY` (default `svc-ln-loan-lifecycle`) for loan eligibility.
  - DPoP is not required for internal client-credentials calls, which are bound by mesh mTLS (platform contract).

Module layout: `risk-domain` (assessment, events, policy, ports) ← `risk-application` (use case) ← `risk-infrastructure` (JPA, transactional outbox, web, security) ← `risk-bootstrap` (Spring Boot app).

## Published events

One topic per aggregate (ADR-019): every event of the `RiskAssessment` aggregate goes to `evt.rsk.risk.v1`, keyed by the assessment id, so one assessment's events stay in order in one partition. The event is named by its `eventType`, in the envelope and in the `eventType` record header, not by the topic.

| Topic | eventType | Key | When |
|---|---|---|---|
| `evt.rsk.risk.v1` | `Risk.RiskAssessment.Assessed.v1` | assessment id (`aggregateId`) | A transaction gets its first decision of record. A retry that returns the stored decision publishes nothing. |

- Consumers read the `eventType` header first, handle the types they subscribe to and skip every other type: commit the offset, never fail and never dead-letter it. A new event type on `evt.rsk.risk.v1` is therefore additive.
- Versioning: adding an optional field or a new event type is a minor change. A breaking change to one event is a new eventType (`Risk.RiskAssessment.Assessed.v2`) on the same topic, published alongside v1 until every consumer has moved. The topic major (`evt.rsk.risk.v2`) changes only when the record key, the partition count or the cleanup policy changes.

- Written to `sc_rsk_decisioning.outbox_event` in the same transaction as the `risk_assessment` row (`TransactionalRiskAssessmentUseCase`, `OutboxRiskEventPublisher`). If the insert loses a race on the unique `transaction_id`, the transaction rolls back and no event is left behind.
- `OutboxRelay` publishes rows in insertion order. One replica relays at a time (Postgres advisory lock). Delivery is at least once, so consumers de-duplicate on `eventId`. Published rows are purged after `risk.outbox.retention` (7 days).
- Runtime settings: `KAFKA_BOOTSTRAP_SERVERS` and `KAFKA_SECURITY_PROTOCOL`; the chart's `kafka.runtime` renders the only Spring profile, `SPRING_PROFILES_ACTIVE`: `msk` (default) gives `kafka-msk`, Amazon MSK with IAM auth through the IRSA role, and `strimzi` gives `kafka-strimzi`, mutual TLS with PEM from `KAFKA_TLS_CERT`/`KAFKA_TLS_KEY`/`KAFKA_TLS_CA` (Secret `kafkaTls.secretName`). The chart vendors the platform datasource / TLS guard (cicd-templates 73e1881, checked in CI by `scripts/ci/verify-vendored-guard.sh`) and refuses any `spring.profiles.*`, `fintechbankx.tls.*` and Kafka client TLS (`spring.kafka.*ssl*`) config key; `KAFKA_TLS_*` come only from the `kafkaTls.secretName` Secret. On by default (only the `local` profile and the test switch `fintechbankx.tls.enforce=false` turn the TLS startup assertions off, neither of which a chart value can set), relay on or off, `KafkaTlsGuard` accepts both: SASL_SSL (MSK) and SSL with the Strimzi KafkaUser certificate; it refuses PLAINTEXT, SASL_PLAINTEXT or an unset `security.protocol` at startup, so a plain-text producer is caught on the first rollout and not on the one that switches the relay on. `OUTBOX_RELAY_ENABLED` is `"false"` in the Helm chart until the mesh contract gives namespace `risk` MSK egress (runbook step 4). The application default is off too; local runs with a broker set `OUTBOX_RELAY_ENABLED=true`. The producer follows the platform client guide: client id `svc-rsk-decisioning`, `acks=all`, idempotent, lz4, `linger.ms=5`, and no topic auto-creation.
- Record headers, UTF-8 text (catalog `common/event-envelope.yaml` `EventHeaders`): `eventType` (equal to the envelope `eventType`), `eventId` and `correlationId` (equal to the envelope fields), and a W3C `traceparent` when the request that raised the event was traced.
- Backlog metric: `outbox_pending_events{service="svc-rsk-decisioning"}`. Alerts live in fintechbankx-platform-observability-sre-operations PR #11 (not merged, commit `eca7aa0`), which also widens the AMP keep regex to the `outbox_` series; this service ships no alert rule. All three are keyed by the `service_id` pod label (`svc-rsk-decisioning`) and routed by squad (risk): OutboxRelayStalled, `max(outbox_oldest_pending_age_seconds) > 900` for 5m, critical; OutboxSendFailures, any increase in `outbox_send_failures_total` over 10m, warning; OutboxEventsParked, any increase in `outbox_parked_events_total` over 15m, warning, no `for` clause (operator parks also fire it). See the runbook, section 4. Per ADR-021 decision 4, a payload error (record too large, not serializable, invalid topic) parks the row (counted once in `outbox_parked_events_total{exception}`; `outbox_parked_rows` is the current number) and the relay continues; every other failure (retriable, authorization, SASL/IAM, unclassified) stops the batch without marking the row and is retried with exponential backoff (up to `risk.outbox.relay.max-backoff`, 5 min), never parked. Manual park and replay steps are in the runbook.
- Status: Proposed. The catalog entry for this contract is proposed in fintechbankx-governance-api-contracts-asyncapi-catalog PR #11 (not merged), and topic `evt.rsk.risk.v1` is not yet created on the platform cluster.

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
