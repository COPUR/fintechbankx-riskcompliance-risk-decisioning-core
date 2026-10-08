package com.bank.risk.domain;

/**
 * Thrown when a transaction id that was already assessed is sent again with
 * any different decision input (amount, currency, high-risk country flag or
 * velocity score). The stored decision is never replaced.
 */
public class TransactionAlreadyAssessedException extends RuntimeException {

    public TransactionAlreadyAssessedException(String transactionId) {
        super("Transaction " + transactionId + " was already assessed with different inputs");
    }
}
