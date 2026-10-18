package com.bank.risk.domain.service;

import com.bank.risk.domain.PaymentType;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Suspicious-pattern limits of one currency, in that currency's units
 * (rsk-policy-v3, monolith parity). An amount strictly above
 * {@code roundAbove} that is a whole multiple of {@code roundUnit} is a
 * suspicious round amount; a MOBILE_PAYMENT strictly above {@code mobileAbove}
 * is a suspicious mobile amount.
 */
public record SuspiciousPatternLimits(BigDecimal roundUnit, BigDecimal roundAbove, BigDecimal mobileAbove) {

    public SuspiciousPatternLimits {
        Objects.requireNonNull(roundUnit, "roundUnit is required");
        Objects.requireNonNull(roundAbove, "roundAbove is required");
        Objects.requireNonNull(mobileAbove, "mobileAbove is required");
        if (roundUnit.signum() <= 0 || roundAbove.signum() <= 0 || mobileAbove.signum() <= 0) {
            throw new IllegalArgumentException("pattern limits must be positive");
        }
    }

    boolean isSuspiciousRoundAmount(BigDecimal amount) {
        return amount.compareTo(roundAbove) > 0 && amount.remainder(roundUnit).signum() == 0;
    }

    boolean isSuspiciousMobileAmount(PaymentType type, BigDecimal amount) {
        return type == PaymentType.MOBILE_PAYMENT && amount.compareTo(mobileAbove) > 0;
    }
}
