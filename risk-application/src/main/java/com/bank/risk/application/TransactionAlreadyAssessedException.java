package com.bank.risk.application;

/**
 * Thrown when a transaction id that was already assessed is sent again with a
 * different amount or currency. The stored decision is never replaced.
 */
public class TransactionAlreadyAssessedException extends RuntimeException {

    public TransactionAlreadyAssessedException(String transactionId) {
        super("Transaction " + transactionId + " was already assessed with a different amount or currency");
    }
}
