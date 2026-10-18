package com.bank.risk.infrastructure.external;

/**
 * Supplies the bearer token an outbound call to another FinTechBankX service
 * carries. This repository had no outbound HTTP adapter before the customer
 * credit client, so there is no shared client-credentials component to reuse;
 * {@link ClientCredentialsTokenProvider} is the production implementation and
 * tests pass a lambda.
 */
@FunctionalInterface
public interface BearerTokenProvider {

    /** A currently valid access token; throws {@link com.bank.risk.domain.CustomerServiceUnavailableException} when none can be obtained. */
    String token();
}
