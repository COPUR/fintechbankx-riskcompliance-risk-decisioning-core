package com.bank.risk.domain;

import java.util.regex.Pattern;

/**
 * The counterparty's country as risk resolved it from the counterparty
 * account: an ISO 3166-1 alpha-2 code, or {@link #UNKNOWN} (code null) when
 * the account is neither a valid IBAN nor an internal account with a configured
 * home country. Unknown is decided as high risk (fail closed).
 */
public record CounterpartyCountry(String code) {

    public static final CounterpartyCountry UNKNOWN = new CounterpartyCountry(null);
    private static final Pattern ALPHA_2 = Pattern.compile("[A-Z]{2}");

    public CounterpartyCountry {
        if (code != null && !ALPHA_2.matcher(code).matches()) {
            throw new IllegalArgumentException("country code must be an upper-case ISO 3166-1 alpha-2 code");
        }
    }

    public static CounterpartyCountry of(String alpha2) {
        if (alpha2 == null) {
            throw new IllegalArgumentException("country code is required; use UNKNOWN for an unresolved country");
        }
        return new CounterpartyCountry(alpha2);
    }

    public boolean isKnown() {
        return code != null;
    }
}
