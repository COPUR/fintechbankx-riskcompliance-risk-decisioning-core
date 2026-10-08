package com.bank.risk.domain;

/**
 * Kind of payment being assessed, stated by the caller. The values are the
 * monolith's payment-context PaymentType, so payments can send theirs as is.
 * rsk-policy-v3 decides a MOBILE_PAYMENT above the mobile limit as suspicious.
 */
public enum PaymentType {
    TRANSFER,
    WIRE_TRANSFER,
    ACH,
    CHECK,
    CREDIT_CARD,
    DEBIT_CARD,
    MOBILE_PAYMENT,
    LOAN_PAYMENT,
    BILL_PAYMENT
}
