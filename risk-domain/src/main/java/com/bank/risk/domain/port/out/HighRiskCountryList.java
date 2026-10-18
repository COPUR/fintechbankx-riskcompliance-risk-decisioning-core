package com.bank.risk.domain.port.out;

import com.bank.risk.domain.HighRiskCountries;

import java.util.Optional;

/**
 * The risk-owned high-risk country list ({@code sc_rsk_decisioning.high_risk_country},
 * loaded by the operator from the FATF list). Empty when no list is loaded;
 * the policy decides whether a loaded list is fresh enough to be a source.
 */
public interface HighRiskCountryList {

    Optional<HighRiskCountries> current();
}
