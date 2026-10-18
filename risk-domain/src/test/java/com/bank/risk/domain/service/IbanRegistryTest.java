package com.bank.risk.domain.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The allow-list of countries that issue IBANs, with each country's registered
 * length (SWIFT IBAN Registry). It is the domain's own constant: a country code
 * outside it, however well-formed, is not a known country.
 */
class IbanRegistryTest {

    @ParameterizedTest(name = "{0} is {1} characters")
    @CsvSource({"GB, 22", "DE, 22", "AE, 23", "IR, 26", "FR, 27", "NO, 15", "LC, 32", "RU, 33", "MT, 31", "SA, 24"})
    void knownCountriesCarryTheirRegisteredLength(String country, int length) {
        assertThat(IbanRegistry.lengthOf(country)).hasValue(length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZ", "QQ", "US", "KP", "XX", "AA"})
    void codesWithoutARegistryEntryAreNotKnown(String code) {
        assertThat(IbanRegistry.lengthOf(code)).isEmpty();
    }

    @Test
    void everyEntryIsAnUpperCaseAlpha2CodeWithAnIbanLength() {
        assertThat(IbanRegistry.countries()).isNotEmpty().allSatisfy(code -> {
            assertThat(code).matches("[A-Z]{2}");
            assertThat(IbanRegistry.lengthOf(code).orElseThrow()).isBetween(15, 34);
        });
        assertThat(IbanRegistry.lengthOf(null)).isEmpty();
        assertThat(IbanRegistry.lengthOf("gb")).as("the registry is keyed by the electronic form").isEmpty();
    }
}
