package com.bank.risk.application;

import com.bank.risk.domain.CustomerCreditProfile;
import com.bank.risk.domain.CustomerNotFoundException;
import com.bank.risk.domain.LoanEligibility;
import com.bank.risk.domain.port.in.LoanEligibilityCommand;
import com.bank.risk.domain.port.in.LoanEligibilityUseCase;
import com.bank.risk.domain.port.out.CustomerCreditProfilePort;
import com.bank.risk.domain.service.LoanEligibilityPolicy;

import java.util.Objects;

/**
 * POST /api/v1/risk/loan-eligibility: read the customer's credit position
 * through the out-port, apply risk's rule. Nothing is stored: the loan
 * service records the decision with its application.
 */
public class LoanEligibilityService implements LoanEligibilityUseCase {

    private final CustomerCreditProfilePort customers;
    private final LoanEligibilityPolicy policy;

    public LoanEligibilityService(CustomerCreditProfilePort customers, LoanEligibilityPolicy policy) {
        this.customers = Objects.requireNonNull(customers, "customers is required");
        this.policy = Objects.requireNonNull(policy, "policy is required");
    }

    @Override
    public LoanEligibility check(LoanEligibilityCommand command) {
        CustomerCreditProfile profile = customers.fetch(command.customerId())
                .orElseThrow(() -> new CustomerNotFoundException(command.customerId()));
        return policy.decide(profile, command.requestedAmount(), command.currency());
    }
}
