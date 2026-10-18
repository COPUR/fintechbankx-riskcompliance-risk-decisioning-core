package com.bank.risk.domain.port.out;

import com.bank.risk.domain.CustomerCreditProfile;

import java.util.Optional;

/**
 * Reads a customer's credit position from the customer context's published
 * API ({@code GET /api/v1/customers/{id}/credit-standing}). Empty when the customer is
 * unknown; {@link com.bank.risk.domain.CustomerServiceUnavailableException}
 * when the customer service cannot be read.
 */
public interface CustomerCreditProfilePort {

    Optional<CustomerCreditProfile> fetch(String customerId);
}
