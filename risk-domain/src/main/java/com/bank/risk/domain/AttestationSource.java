package com.bank.risk.domain;

/**
 * Who vouches for the facts a decision rests on. Risk does not yet verify
 * country risk or velocity itself: it applies its thresholds to what the
 * caller states, so every decision is CALLER_ATTESTED and records who
 * attested (the calling client's azp, or a staff member's subject).
 */
public enum AttestationSource {
    CALLER_ATTESTED
}
