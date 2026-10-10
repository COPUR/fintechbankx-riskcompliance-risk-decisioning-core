package com.bank.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * svc-rsk-decisioning: transaction risk decisions, extracted from
 * enterprise-loan-management-system. Payment services ask it to assess a
 * transaction; each decision is stored once and returned on retries.
 *
 * <p>With the first argument {@code migrate} the image runs as the chart's
 * migration Job instead ({@link DatabaseMigration}) and exits.
 */
@SpringBootApplication
public class RiskDecisioningApplication {

    public static void main(String[] args) {
        if (DatabaseMigration.isRequested(args)) {
            System.exit(DatabaseMigration.run(DatabaseMigration.arguments(args)));
        }
        SpringApplication.run(RiskDecisioningApplication.class, args);
    }
}
