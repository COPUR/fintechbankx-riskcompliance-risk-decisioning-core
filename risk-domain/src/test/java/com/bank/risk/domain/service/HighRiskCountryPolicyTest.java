package com.bank.risk.domain.service;

import com.bank.risk.domain.CounterpartyCountry;
import com.bank.risk.domain.HighRiskCountries;
import com.bank.risk.domain.RiskSourceUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * highRiskCountry is read from the risk-owned list (sc_rsk_decisioning.high_risk_country,
 * loaded from the FATF list by an operator). An empty list or one older than
 * RISK_COUNTRY_LIST_MAX_AGE (default P35D) makes the source unavailable: the
 * endpoint answers 503 RISK_SOURCE_UNAVAILABLE instead of deciding on stale
 * facts. An unknown counterparty country is high risk (fail closed).
 */
class HighRiskCountryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofDays(35);

    private final HighRiskCountryPolicy policy = new HighRiskCountryPolicy(MAX_AGE, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Optional<HighRiskCountries> list(Instant loadedAt, String... codes) {
        return Optional.of(new HighRiskCountries(Set.of(codes), "FATF", "2026-06", loadedAt));
    }

    @Test
    void aCountryOnAFreshListIsHighRisk() {
        assertThat(policy.isHighRisk(CounterpartyCountry.of("IR"), list(NOW.minus(Duration.ofDays(1)), "IR", "KP"))).isTrue();
        assertThat(policy.isHighRisk(CounterpartyCountry.of("GB"), list(NOW.minus(Duration.ofDays(1)), "IR", "KP"))).isFalse();
    }

    @Test
    void anUnknownCounterpartyCountryIsHighRiskEvenWhenTheListIsFresh() {
        assertThat(policy.isHighRisk(CounterpartyCountry.UNKNOWN, list(NOW, "IR"))).isTrue();
    }

    @Test
    void anEmptyListIsNotASource() {
        assertThatThrownBy(() -> policy.isHighRisk(CounterpartyCountry.of("GB"), Optional.empty()))
            .isInstanceOf(RiskSourceUnavailableException.class)
            .hasMessageContaining("high-risk country list")
            .hasMessageContaining("empty");
    }

    @Test
    void aListOlderThanTheMaximumAgeIsNotASource() {
        Instant tooOld = NOW.minus(MAX_AGE).minusSeconds(1);

        assertThatThrownBy(() -> policy.isHighRisk(CounterpartyCountry.of("GB"), list(tooOld, "IR")))
            .isInstanceOf(RiskSourceUnavailableException.class)
            .hasMessageContaining("high-risk country list")
            .hasMessageContaining("P35D");
    }

    @Test
    void aListExactlyAtTheMaximumAgeIsStillASource() {
        assertThat(policy.isHighRisk(CounterpartyCountry.of("IR"), list(NOW.minus(MAX_AGE), "IR"))).isTrue();
    }

    /** Even an unknown country is answered 503, not BLOCK, on a stale list: the decision of record must not rest on no source. */
    @Test
    void staleListWinsOverTheUnknownCountryRule() {
        assertThatThrownBy(() -> policy.isHighRisk(CounterpartyCountry.UNKNOWN, Optional.empty()))
            .isInstanceOf(RiskSourceUnavailableException.class);
    }

    @Test
    void theListValueObjectValidatesItself() {
        assertThatThrownBy(() -> new HighRiskCountries(Set.of("IR"), "FATF", "2026-06", null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("loadedAt");
        assertThatThrownBy(() -> new HighRiskCountries(Set.of("Iran"), "FATF", "2026-06", NOW))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("alpha-2");
        assertThatThrownBy(() -> new HighRiskCountryPolicy(Duration.ZERO, Clock.systemUTC()))
            .isInstanceOf(IllegalArgumentException.class);
        HighRiskCountries list = new HighRiskCountries(Set.of("IR"), "FATF", "2026-06", NOW);
        assertThat(list.contains("IR")).isTrue();
        assertThat(list.contains("GB")).isFalse();
    }
}
