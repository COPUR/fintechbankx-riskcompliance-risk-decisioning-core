package com.bank.risk.domain.service;

import com.bank.risk.domain.CounterpartyCountry;
import com.bank.risk.domain.HighRiskCountries;
import com.bank.risk.domain.RiskSourceUnavailableException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides highRiskCountry from the risk-owned list. The list is a source only
 * when it is loaded and no older than {@code maxAge} (RISK_COUNTRY_LIST_MAX_AGE,
 * default P35D: the FATF publishes three times a year and the operator reloads
 * after each plenary; a list nobody reloaded is not trusted). Otherwise the
 * decision is refused with {@link RiskSourceUnavailableException}, whatever
 * the country. An unknown country on a fresh list is high risk (fail closed).
 */
public final class HighRiskCountryPolicy {

    public static final String SOURCE = "high-risk country list";

    private final Duration maxAge;
    private final Clock clock;

    public HighRiskCountryPolicy(Duration maxAge, Clock clock) {
        Objects.requireNonNull(maxAge, "maxAge is required");
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be positive");
        }
        this.maxAge = maxAge;
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public boolean isHighRisk(CounterpartyCountry country, Optional<HighRiskCountries> list) {
        HighRiskCountries countries = requireFresh(list);
        if (!country.isKnown()) {
            return true;
        }
        return countries.contains(country.code());
    }

    /** The list used for a decision, or the refusal; exposed so the application can record the list version. */
    public HighRiskCountries requireFresh(Optional<HighRiskCountries> list) {
        if (list.isEmpty() || list.get().isEmpty()) {
            throw new RiskSourceUnavailableException(SOURCE,
                    "the list is empty; load it with scripts/ops/load-high-risk-countries.sh");
        }
        HighRiskCountries countries = list.get();
        Instant oldestAccepted = clock.instant().minus(maxAge);
        if (countries.loadedAt().isBefore(oldestAccepted)) {
            throw new RiskSourceUnavailableException(SOURCE, "the list (" + countries.listSource() + " " + countries.listVersion()
                    + ") was loaded at " + countries.loadedAt() + ", older than " + isoAge(maxAge) + "; reload it");
        }
        return countries;
    }

    /** Whole days as the operator configures them (P35D), not Duration's PT840H. */
    static String isoAge(Duration age) {
        if (age.toSeconds() % Duration.ofDays(1).toSeconds() == 0) {
            return "P" + age.toDays() + "D";
        }
        return age.toString();
    }
}
