package com.bank.risk.domain.service;

import com.bank.risk.domain.CounterpartyCountry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/v2/risk/assess: risk resolves the counterparty's country itself
 * (screening-facts plan, "Risk"). An IBAN gives its ISO 13616 country prefix
 * once the mod-97 checksum holds; an internal ACC- account is the bank's home
 * country (RISK_HOME_COUNTRY); anything else is unknown, which the policy
 * treats as high risk.
 */
class CounterpartyCountryResolverTest {

    private final CounterpartyCountryResolver resolver = new CounterpartyCountryResolver("AE");

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "GB82WEST12345698765432, GB",
        "DE89370400440532013000, DE",
        "AE070331234567890123456, AE",
        "IR580540105180021273113007, IR",
        // Spaces and lower case are presentation: the same account.
        "'gb82 west 1234 5698 7654 32', GB",
    })
    void aValidIbanResolvesToItsCountryPrefix(String iban, String country) {
        assertThat(resolver.resolve(iban)).isEqualTo(CounterpartyCountry.of(country));
    }

    /** The checksum is checked before the prefix is trusted: one changed digit is not an IBAN at all. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "GB82WEST12345698765433",   // last digit changed
        "GB83WEST12345698765432",   // check digits changed
        "DE89370400440532013001",
        "GB82WEST1234",             // too short for an IBAN
        "G882WEST12345698765432",   // country letters must be letters
    })
    void anIbanWithABrokenChecksumOrShapeIsUnknown(String notAnIban) {
        assertThat(resolver.resolve(notAnIban)).isEqualTo(CounterpartyCountry.UNKNOWN);
        assertThat(resolver.resolve(notAnIban).isKnown()).isFalse();
    }

    @Test
    void anInternalAccountIsTheHomeCountry() {
        assertThat(resolver.resolve("ACC-1234567890")).isEqualTo(CounterpartyCountry.of("AE"));
        assertThat(resolver.resolve(" acc-42 ")).as("prefix match is case-insensitive, trimmed").isEqualTo(CounterpartyCountry.of("AE"));
    }

    /** Fail closed: without RISK_HOME_COUNTRY an internal account has no country, so it is high risk. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "AED", "a"})
    void withoutAValidHomeCountryAnInternalAccountIsUnknown(String home) {
        assertThat(new CounterpartyCountryResolver(home).resolve("ACC-1")).isEqualTo(CounterpartyCountry.UNKNOWN);
        assertThat(new CounterpartyCountryResolver(null).resolve("ACC-1")).isEqualTo(CounterpartyCountry.UNKNOWN);
    }

    @Test
    void theHomeCountryIsNormalisedToUpperCase() {
        assertThat(new CounterpartyCountryResolver("ae").resolve("ACC-1")).isEqualTo(CounterpartyCountry.of("AE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "12345678", "wallet:abc", "ACC12345"})
    void anythingElseIsUnknown(String account) {
        assertThat(resolver.resolve(account)).isEqualTo(CounterpartyCountry.UNKNOWN);
        assertThat(resolver.resolve(null)).isEqualTo(CounterpartyCountry.UNKNOWN);
    }

    @Test
    void aCountryCodeMustBeTwoUpperCaseLetters() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CounterpartyCountry.of("gb"))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CounterpartyCountry.of("GBR"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(CounterpartyCountry.UNKNOWN.code()).isNull();
    }
}
