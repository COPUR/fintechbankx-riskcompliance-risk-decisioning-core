package com.bank.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * svc-rsk-decisioning: transaction risk decisions, extracted from
 * enterprise-loan-management-system. Payment services ask it to assess a
 * transaction; each decision is stored once and returned on retries.
 */
@SpringBootApplication
public class RiskDecisioningApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskDecisioningApplication.class, args);
    }
}
