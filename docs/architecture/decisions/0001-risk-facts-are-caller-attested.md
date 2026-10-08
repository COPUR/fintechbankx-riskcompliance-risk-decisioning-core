# 0001. Risk facts are caller-attested until risk resolves them itself

- Status: Proposed
- Date: 2026-10-08
- Owner: Risk and Compliance Decisioning Squad
- Scope: `svc-rsk-decisioning`, `POST /api/v1/risk/assess`

## Context

The transaction risk decision (ALLOW / REVIEW / BLOCK) is scored from four
inputs: the amount, its currency, whether the counterparty country is high
risk, and a velocity score. Today the calling service (payments) sends all of
them in the request. `svc-rsk-decisioning` looks none of them up: it keeps no
country risk list and computes no velocity, although both are risk's own
capability (`fbx-domain-map`: risk owns rules and decision logs).

So a stored decision proves only what the rules concluded from what the caller
stated. A decision log that hides this would overstate what risk checked.

## Decision

1. `highRiskCountry` and `velocityScore` are required. An omitted or null
   value is a `400 INVALID_REQUEST`, never read as the low-risk `false` or `0`.
   A caller that cannot read its fact sources must not call assess; it answers
   retryable instead, because the decision of record is insert-only and a
   re-assessment of the same transaction with different facts is refused
   (`409 TRANSACTION_ALREADY_ASSESSED`).
2. Every decision records where its facts came from: `attestation_source` on
   `sc_rsk_decisioning.risk_assessment` (V5), `AttestationSource` in the domain,
   and the additive `attestationSource` field on the API response. The only
   value today is `CALLER_ATTESTED`. `attested_by` records who stated the
   facts: the client id (`azp`) of a SERVICE caller, or the subject of a staff
   caller. Both columns are NOT NULL.
3. The facts (amount, currency, both flags) and the policy version
   (`rule_set_version`, V7; `rsk-policy-v2` since amount thresholds became per
   currency, `rsk-policy-v3` since the suspicious patterns, decision 6) are
   stored with the decision. `paymentType` (V9) is a fact too. A retry must repeat every fact to get
   the stored decision; anything else is `409 TRANSACTION_ALREADY_ASSESSED`. The
   caller and the rule-set version are recorded, not compared on retry.
4. Amount thresholds are per currency, and only USD is configured
   (10000 / 50000, the values the policy always used; the monolith evidence
   says the home currency is USD). An amount in any other currency is never
   decided as low risk: the amount rules are skipped, reason
   `UNSUPPORTED_CURRENCY` is added and the decision is at least `REVIEW`. The
   country and velocity rules still apply. Reversible: adding a currency's
   thresholds is a policy change with a new `rule_set_version`.
5. `evt.rsk.risk.assessed.v1` carries `attestationSource` (optional, contract
   1.0.0). Who attested (`attestedBy`) stays behind the risk API.
6. `rsk-policy-v3` keeps v2's rules and restores the monolith's fraud refusals
   (enterprise-loan-management-system
   `payment-context/payment-infrastructure/.../external/FraudDetectionServiceAdapter.java:98-117`,
   `isSuspiciousPattern`), which regression LP-05 found missing. In USD only,
   like the thresholds: an amount above 10000 that is a multiple of 1000 is
   `BLOCK` with `SUSPICIOUS_ROUND_AMOUNT` (10000.00 is not), and a
   `MOBILE_PAYMENT` above 5000 is `BLOCK` with `SUSPICIOUS_MOBILE_AMOUNT`
   (5000.00 is not). The score stays v2's; the pattern decides. This needs the
   caller-stated `paymentType` (the monolith's PaymentType values), which is
   required and fails closed like decision 1.
7. The monolith's other payment refusals (`isValidPayment`: score above 80,
   amount above 100000, same account) are not ported. Same account is refused
   by payments before risk is called. Every USD amount above 50000 already
   scores 50 here, which is `REVIEW`, and payments refuses any decision other
   than `ALLOW`. So nothing the monolith refused gets through. The service is
   deliberately stricter: the monolith accepted 50000 < amount <= 100000 by day
   for a non-wire, non-round payment, and here that amount goes to manual
   review instead. Regression records this as an intentional rule owned by
   risk. Its night and wire-transfer score additions change no outcome that
   is not already refused, so they are not ported either.

## Consequences

- Auditors can tell a caller-attested decision from one risk verified.
- A caller cannot get an ALLOW for one set of facts and reuse it for another.
- Risk still depends on callers telling the truth about country risk and
  velocity. This is the main risk this record accepts, until the follow-ups
  below land.
- When risk resolves facts itself, new `AttestationSource` values are added
  (for example `VELOCITY_FROM_PAYMENT_EVENTS`, `COUNTRY_RISK_LIST`). Clients
  must accept unknown values (documented in the OpenAPI description).

## Follow-ups

- Compute velocity in risk from payment events (`evt.pay.*`, consumed through
  the platform catalog) instead of the caller's `velocityScore`.
- Own the country risk list in risk (source and update process to be agreed
  with Group Risk) and resolve `highRiskCountry` from the counterparty country.
- Amount thresholds exist for USD only; other currencies get REVIEW with
  `UNSUPPORTED_CURRENCY`. Configure thresholds for each currency the bank
  settles in once the home-currency decision is made.

## Reversibility

Reversible: the columns, fields and enum can gain values without breaking
callers. Removing them would discard decision evidence, so that direction needs
owner approval.
