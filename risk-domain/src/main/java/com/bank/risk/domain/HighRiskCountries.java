package com.bank.risk.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The risk-owned high-risk country list as loaded by the operator
 * ({@code sc_rsk_decisioning.high_risk_country}): the ISO alpha-2 codes, where
 * they came from (for example FATF), which publication, and when the load
 * ran. A list is only a decision source while it is fresh
 * ({@link com.bank.risk.domain.service.HighRiskCountryPolicy}).
 */
public record HighRiskCountries(Set<String> countryCodes, String listSource, String listVersion, Instant loadedAt) {

    private static final Pattern ALPHA_2 = Pattern.compile("[A-Z]{2}");

    public HighRiskCountries {
        Objects.requireNonNull(countryCodes, "countryCodes are required");
        Objects.requireNonNull(loadedAt, "loadedAt is required");
        if (listSource == null || listSource.isBlank()) {
            throw new IllegalArgumentException("listSource is required");
        }
        if (listVersion == null || listVersion.isBlank()) {
            throw new IllegalArgumentException("listVersion is required");
        }
        for (String code : countryCodes) {
            if (code == null || !ALPHA_2.matcher(code).matches()) {
                throw new IllegalArgumentException("country codes must be upper-case ISO 3166-1 alpha-2, not " + code);
            }
        }
        countryCodes = Set.copyOf(countryCodes);
    }

    public boolean contains(String alpha2) {
        return countryCodes.contains(alpha2);
    }

    public boolean isEmpty() {
        return countryCodes.isEmpty();
    }
}
