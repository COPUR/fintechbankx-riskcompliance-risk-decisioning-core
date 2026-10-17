package com.bank.risk.domain.service;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Amount thresholds of one currency, in that currency's units. An amount
 * strictly above {@code high} scores HIGH_AMOUNT, strictly above
 * {@code veryHigh} also VERY_HIGH_AMOUNT.
 */
public record AmountThresholds(BigDecimal high, BigDecimal veryHigh) {

    public AmountThresholds {
        Objects.requireNonNull(high, "high is required");
        Objects.requireNonNull(veryHigh, "veryHigh is required");
        if (high.signum() <= 0) {
            throw new IllegalArgumentException("high must be positive");
        }
        if (veryHigh.compareTo(high) < 0) {
            throw new IllegalArgumentException("veryHigh must not be below high");
        }
    }
}
