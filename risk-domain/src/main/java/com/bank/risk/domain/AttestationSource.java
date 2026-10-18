package com.bank.risk.domain;

/**
 * Who vouches for the facts a decision rests on (ADR 0001).
 * <ul>
 * <li>{@link #CALLER_ATTESTED}: highRiskCountry and velocityScore were stated
 * by the caller of {@code POST /api/v1/risk/assess} and not verified by risk;
 * {@code attested_by} names that caller.</li>
 * <li>{@link #RISK_RESOLVED}: risk resolved both facts itself for
 * {@code POST /api/v2/risk/assess}: the counterparty country from the account
 * against the risk-owned high-risk country list, and the velocity from its
 * own decisions of record for the customer; {@code attested_by} names the
 * caller that asked.</li>
 * </ul>
 */
public enum AttestationSource {
    CALLER_ATTESTED,
    RISK_RESOLVED
}
