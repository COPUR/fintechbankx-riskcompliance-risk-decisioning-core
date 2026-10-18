package com.bank.risk.domain.port.in;

import com.bank.risk.domain.LoanEligibility;

/** {@code POST /api/v1/risk/loan-eligibility}: risk decides whether a customer may borrow an amount. */
public interface LoanEligibilityUseCase {

    LoanEligibility check(LoanEligibilityCommand command);
}
