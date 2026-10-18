package com.bank.risk.domain;

/**
 * The customer service could not be read (network, a 5xx, a token that could
 * not be obtained, an unreadable body). Loan eligibility is then not decided:
 * the caller gets a retryable 503 CUSTOMER_SERVICE_UNAVAILABLE. The message
 * is for the log; the API answer never carries it.
 */
public class CustomerServiceUnavailableException extends RuntimeException {

    public CustomerServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
