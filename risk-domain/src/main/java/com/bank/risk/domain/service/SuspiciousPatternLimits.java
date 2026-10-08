package com.bank.risk.domain.service;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Suspicious-pattern limits of one currency, in that currency's units
 * (rsk-policy-v3, monolith parity). An amount strictly above
 * {@code roundAbove} that is a whole multiple of {@code roundUnit} is a
 * suspicious round amount.
 */
public record SuspiciousPatternLimits(BigDecimal roundUnit, BigDecimal roundAbove) {

    public SuspiciousPatternLimits {
        Objects.requireNonNull(roundUnit, "roundUnit is required");
        Objects.requireNonNull(roundAbove, "roundAbove is required");
        if (roundUnit.signum() <= 0 || roundAbove.signum() <= 0) {
            throw new IllegalArgumentException("pattern limits must be positive");
        }
    }

    boolean isSuspiciousRoundAmount(BigDecimal amount) {
        return amount.compareTo(roundAbove) > 0 && amount.remainder(roundUnit).signum() == 0;
    }
}
