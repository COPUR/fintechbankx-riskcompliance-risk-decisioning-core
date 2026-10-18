package com.bank.risk.domain.service;

import com.bank.risk.domain.CounterpartyCountry;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves the counterparty's country from the account the caller sends
 * (screening-facts plan, "Country"):
 * <ul>
 * <li>an IBAN (ISO 13616): its two-letter prefix, but only when that country
 * has an {@link IbanRegistry} entry, the IBAN has that country's registered
 * length and the mod-97 checksum holds. The checksum alone proves nothing
 * (check digits can be computed for any prefix), so ZZ or QQ with valid check
 * digits is unknown;</li>
 * <li>an internal account: exactly the shape the ledger's AccountId generates,
 * {@value #INTERNAL_ACCOUNT_PREFIX} followed by a UUID; it resolves to the
 * bank's home country (RISK_HOME_COUNTRY). Without a valid home country, or
 * with anything else behind the prefix (an IBAN, a number), it is unknown;</li>
 * <li>anything else: {@link CounterpartyCountry#UNKNOWN}, which the policy
 * decides as high risk.</li>
 * </ul>
 * Surrounding blanks, inner spaces and letter case of an IBAN are presentation
 * (the print form) and are normalised to the electronic form first.
 */
public final class CounterpartyCountryResolver {

    public static final String INTERNAL_ACCOUNT_PREFIX = "ACC-";
    /** payment-domain AccountId: "ACC-" + UUID.randomUUID(); UUID hex digits in either case. */
    private static final Pattern INTERNAL_ACCOUNT = Pattern.compile(
            "ACC-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
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
        String trimmed = counterpartyAccount.trim();
        if (INTERNAL_ACCOUNT.matcher(trimmed).matches()) {
            return homeCountry;
        }
        String iban = trimmed.replace(" ", "").toUpperCase(Locale.ROOT);
        if (isRegisteredIban(iban)) {
            return CounterpartyCountry.of(iban.substring(0, 2));
        }
        return CounterpartyCountry.UNKNOWN;
    }

    /** A registered country, at its registered length, with a holding checksum. */
    static boolean isRegisteredIban(String normalised) {
        if (!IBAN_SHAPE.matcher(normalised).matches()) {
            return false;
        }
        return IbanRegistry.lengthOf(normalised.substring(0, 2))
                .filter(length -> length == normalised.length())
                .isPresent()
                && isValidIban(normalised);
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
