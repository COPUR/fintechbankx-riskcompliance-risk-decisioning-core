package com.bank.risk.domain;

/**
 * A fact source risk depends on cannot answer (the high-risk country list is
 * empty or older than its maximum age). No decision of record is made on it;
 * the caller gets a retryable 503 RISK_SOURCE_UNAVAILABLE.
 */
public class RiskSourceUnavailableException extends RuntimeException {

    private final String source;

    public RiskSourceUnavailableException(String source, String detail) {
        super(source + " is unavailable: " + detail);
        this.source = source;
    }

    public String source() {
        return source;
    }
}
