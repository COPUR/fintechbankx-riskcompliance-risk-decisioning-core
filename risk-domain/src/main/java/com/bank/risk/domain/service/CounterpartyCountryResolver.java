package com.bank.risk.domain.service;

import com.bank.risk.domain.CounterpartyCountry;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves the counterparty's country from the account the caller sends
 * (screening-facts plan, "Country"):
 * <ul>
 * <li>an IBAN (ISO 13616): its two-letter prefix, but only after the mod-97
 * checksum holds, so a mistyped or forged account does not borrow a country;</li>
 * <li>an internal account ({@value #INTERNAL_ACCOUNT_PREFIX}...): the bank's
 * home country (RISK_HOME_COUNTRY); without a valid home country configured
 * it is unknown;</li>
 * <li>anything else: {@link CounterpartyCountry#UNKNOWN}, which the policy
 * decides as high risk.</li>
 * </ul>
 * Spaces and case are presentation and are normalised first.
 */
public final class CounterpartyCountryResolver {

    public static final String INTERNAL_ACCOUNT_PREFIX = "ACC-";
    private static final Pattern IBAN_SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
    private static final Pattern ALPHA_2 = Pattern.compile("[A-Z]{2}");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private final CounterpartyCountry homeCountry;

    /** @param homeCountry RISK_HOME_COUNTRY; null, blank or not alpha-2 means no home country is set. */
    public CounterpartyCountryResolver(String homeCountry) {
        String normalised = homeCountry == null ? "" : homeCountry.trim().toUpperCase(Locale.ROOT);
        this.homeCountry = ALPHA_2.matcher(normalised).matches() ? CounterpartyCountry.of(normalised) : CounterpartyCountry.UNKNOWN;
    }

    public boolean hasHomeCountry() {
        return homeCountry.isKnown();
    }

    public CounterpartyCountry resolve(String counterpartyAccount) {
        if (counterpartyAccount == null) {
            return CounterpartyCountry.UNKNOWN;
        }
        String account = counterpartyAccount.replace(" ", "").trim().toUpperCase(Locale.ROOT);
        if (account.startsWith(INTERNAL_ACCOUNT_PREFIX)) {
            return homeCountry;
        }
        if (isValidIban(account)) {
            return CounterpartyCountry.of(account.substring(0, 2));
        }
        return CounterpartyCountry.UNKNOWN;
    }

    /** ISO 13616 / ISO 7064 mod 97-10: move the first four characters to the end, letters A-Z become 10-35, remainder 1. */
    static boolean isValidIban(String normalised) {
        if (!IBAN_SHAPE.matcher(normalised).matches()) {
            return false;
        }
        String rearranged = normalised.substring(4) + normalised.substring(0, 4);
        StringBuilder digits = new StringBuilder(rearranged.length() * 2);
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                digits.append(c - 'A' + 10);
            }
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).equals(BigInteger.ONE);
    }
}
