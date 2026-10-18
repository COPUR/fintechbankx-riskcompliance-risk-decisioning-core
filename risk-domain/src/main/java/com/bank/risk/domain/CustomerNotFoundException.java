package com.bank.risk.domain;

/** The customer service knows no customer with this id (404 from its credit endpoint). */
public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(String customerId) {
        super("Customer " + customerId + " not found");
    }
}
