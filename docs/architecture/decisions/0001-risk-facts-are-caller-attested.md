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
2. Every decision records where its facts came from: `attestation_source` on
   `sc_rsk_decisioning.risk_assessment` (V5), `AttestationSource` in the domain,
   and the additive `attestationSource` field on the API response. The only
   value today is `CALLER_ATTESTED`. `attested_by` records who stated the
   facts: the client id (`azp`) of a SERVICE caller, or the subject of a staff
   caller. Decisions stored before V5 keep `attested_by` NULL.
3. The facts (amount, currency, both flags) and the policy version
   (`rule_set_version`, V7; `rsk-policy-v2` since amount thresholds became per
   currency) are stored with the decision. A retry must repeat every fact to get
   the stored decision; anything else is `409 TRANSACTION_ALREADY_ASSESSED`. The
   caller and the rule-set version are recorded, not compared on retry.
4. The attestation is not yet published on `evt.rsk.risk.assessed.v1`; readers
   get it through the risk API.

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
- Decide whether to add `attestationSource` to the Assessed event (additive).

## Reversibility

Reversible: the columns, fields and enum can gain values without breaking
callers. Removing them would discard decision evidence, so that direction needs
owner approval.
