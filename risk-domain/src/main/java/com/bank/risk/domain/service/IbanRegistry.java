package com.bank.risk.domain.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The countries that issue IBANs and each country's IBAN length, after the
 * SWIFT IBAN Registry (ISO 13616 registration authority). It is the domain's
 * allow-list for counterparty countries: an IBAN prefix outside it, or an IBAN
 * of another length, is not a known country, however well its check digits
 * hold (mod-97 is a typo check, and anyone can compute check digits for ZZ).
 * Reviewed with each registry release; a country that joins is added here.
 */
public final class IbanRegistry {

    private static final Map<String, Integer> LENGTH_BY_COUNTRY = Map.ofEntries(
            Map.entry("AD", 24), Map.entry("AE", 23), Map.entry("AL", 28), Map.entry("AT", 20), Map.entry("AZ", 28),
            Map.entry("BA", 20), Map.entry("BE", 16), Map.entry("BG", 22), Map.entry("BH", 22), Map.entry("BI", 27),
            Map.entry("BR", 29), Map.entry("BY", 28), Map.entry("CH", 21), Map.entry("CR", 22), Map.entry("CY", 28),
            Map.entry("CZ", 24), Map.entry("DE", 22), Map.entry("DJ", 27), Map.entry("DK", 18), Map.entry("DO", 28),
            Map.entry("EE", 20), Map.entry("EG", 29), Map.entry("ES", 24), Map.entry("FI", 18), Map.entry("FK", 18),
            Map.entry("FO", 18), Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("GE", 22), Map.entry("GI", 23),
            Map.entry("GL", 18), Map.entry("GR", 27), Map.entry("GT", 28), Map.entry("HN", 28), Map.entry("HR", 21),
            Map.entry("HU", 28), Map.entry("IE", 22), Map.entry("IL", 23), Map.entry("IQ", 23), Map.entry("IR", 26),
            Map.entry("IS", 26), Map.entry("IT", 27), Map.entry("JO", 30), Map.entry("KW", 30), Map.entry("KZ", 20),
            Map.entry("LB", 28), Map.entry("LC", 32), Map.entry("LI", 21), Map.entry("LT", 20), Map.entry("LU", 20),
            Map.entry("LV", 21), Map.entry("LY", 25), Map.entry("MC", 27), Map.entry("MD", 24), Map.entry("ME", 22),
            Map.entry("MK", 19), Map.entry("MN", 20), Map.entry("MR", 27), Map.entry("MT", 31), Map.entry("MU", 30),
            Map.entry("NI", 28), Map.entry("NL", 18), Map.entry("NO", 15), Map.entry("OM", 23), Map.entry("PK", 24),
            Map.entry("PL", 28), Map.entry("PS", 29), Map.entry("PT", 25), Map.entry("QA", 29), Map.entry("RO", 24),
            Map.entry("RS", 22), Map.entry("RU", 33), Map.entry("SA", 24), Map.entry("SC", 31), Map.entry("SD", 18),
            Map.entry("SE", 24), Map.entry("SI", 19), Map.entry("SK", 24), Map.entry("SM", 27), Map.entry("SO", 23),
            Map.entry("ST", 25), Map.entry("SV", 28), Map.entry("TL", 23), Map.entry("TN", 24), Map.entry("TR", 26),
            Map.entry("UA", 29), Map.entry("VA", 22), Map.entry("VG", 24), Map.entry("XK", 20), Map.entry("YE", 30));

    private IbanRegistry() {
    }

    /** The registered IBAN length of a country (electronic form, upper case), empty for a code without an entry. */
    public static Optional<Integer> lengthOf(String countryCode) {
        return countryCode == null ? Optional.empty() : Optional.ofNullable(LENGTH_BY_COUNTRY.get(countryCode));
    }

    public static Set<String> countries() {
        return LENGTH_BY_COUNTRY.keySet();
    }
}
