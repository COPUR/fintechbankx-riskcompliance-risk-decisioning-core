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

    private static final String INTERNAL = "ACC-9b2f6c1e-4d3a-4f8b-9c7d-2e1f0a9b8c7d";
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

    /**
     * Mod-97 is a typo check, not proof of a country: anyone can compute check digits for any prefix. Only a
     * country with an IBAN-registry entry, at that country's registered length, is a known country
     * (DevSecOps B-1); ZZ and QQ pass mod-97 and are unknown, so high risk. US has no IBAN; KP neither.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "ZZ1812345678901234",          // mod-97 holds, ZZ is not an ISO 3166-1 country
        "QQ73ABCDEFGHIJK",             // mod-97 holds, QQ is user-assigned, not a country
        "US02WEST12345698765432",      // mod-97 holds, the United States has no IBAN
        "KP301234567890123456",        // mod-97 holds, no IBAN registry entry
        "GB49WEST123456987654321",     // mod-97 holds, 23 characters: GB IBANs are 22
        "GB88WEST1234569876543",       // mod-97 holds, 21 characters
    })
    void aPrefixWithoutAnIbanRegistryEntryOrAtTheWrongLengthIsUnknown(String forged) {
        assertThat(CounterpartyCountryResolver.isValidIban(forged.toUpperCase(java.util.Locale.ROOT)))
            .as("the checksum alone holds for %s", forged).isTrue();
        assertThat(resolver.resolve(forged)).isEqualTo(CounterpartyCountry.UNKNOWN);
    }

    /** Lower case is the print form of the same account (ISO 13616 electronic form is upper case). */
    @Test
    void aLowerCaseIbanIsTheSameAccountAsItsUpperCaseForm() {
        assertThat(resolver.resolve("gb82west12345698765432")).isEqualTo(CounterpartyCountry.of("GB"));
        assertThat(resolver.resolve("ir580540105180021273113007")).isEqualTo(CounterpartyCountry.of("IR"));
        assertThat(resolver.resolve("zz1812345678901234")).as("lower case does not make ZZ a country").isEqualTo(CounterpartyCountry.UNKNOWN);
    }

    /**
     * An internal account is exactly what the ledger's AccountId generates
     * (payment-domain AccountId.java:26: "ACC-" + UUID); the prefix alone vouches for nothing (DevSecOps S-1).
     */
    @Test
    void anInternalAccountIsTheHomeCountry() {
        assertThat(resolver.resolve("ACC-9b2f6c1e-4d3a-4f8b-9c7d-2e1f0a9b8c7d")).isEqualTo(CounterpartyCountry.of("AE"));
        assertThat(resolver.resolve(" ACC-9B2F6C1E-4D3A-4F8B-9C7D-2E1F0A9B8C7D ")).as("trimmed; hex digits in either case")
            .isEqualTo(CounterpartyCountry.of("AE"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "ACC-1234567890",                                   // not a UUID
        "acc-9b2f6c1e-4d3a-4f8b-9c7d-2e1f0a9b8c7d",         // the prefix is upper case in every generated id
        "ACC-IR580540105180021273113007",                   // an Iranian IBAN behind the prefix
        "ACC-",
        "ACC-9b2f6c1e-4d3a-4f8b-9c7d-2e1f0a9b8c7",          // one hex digit short
        "ACC-9b2f6c1e-4d3a-4f8b-9c7d-2e1f0a9b8c7dX",
    })
    void anAccountThatOnlyLooksInternalIsUnknown(String account) {
        assertThat(resolver.resolve(account)).isEqualTo(CounterpartyCountry.UNKNOWN);
    }

    /** Fail closed: without RISK_HOME_COUNTRY an internal account has no country, so it is high risk. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "AED", "a"})
    void withoutAValidHomeCountryAnInternalAccountIsUnknown(String home) {
        assertThat(new CounterpartyCountryResolver(home).resolve(INTERNAL)).isEqualTo(CounterpartyCountry.UNKNOWN);
        assertThat(new CounterpartyCountryResolver(null).resolve(INTERNAL)).isEqualTo(CounterpartyCountry.UNKNOWN);
    }

    @Test
    void theHomeCountryIsNormalisedToUpperCase() {
        assertThat(new CounterpartyCountryResolver("ae").resolve(INTERNAL)).isEqualTo(CounterpartyCountry.of("AE"));
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
