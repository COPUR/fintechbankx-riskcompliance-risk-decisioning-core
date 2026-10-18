package com.bank.risk.infrastructure.web;

import com.bank.risk.domain.port.in.LoanEligibilityCommand;
import com.bank.risk.domain.port.in.LoanEligibilityUseCase;
import com.bank.risk.infrastructure.web.dto.LoanEligibilityRequest;
import com.bank.risk.infrastructure.web.dto.LoanEligibilityResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/risk/loan-eligibility ("Risk decides"): the loan service asks
 * whether a customer may borrow an amount; risk reads the customer's credit
 * position and applies its rule. Callers: SERVICE clients on
 * SERVICE_CALLERS_ELIGIBILITY (default svc-ln-loan-lifecycle), and bank staff.
 * Nothing is stored here.
 */
@RestController
@RequestMapping("/api/v1/risk/loan-eligibility")
public class LoanEligibilityController {
    static final String CALLERS = "hasAnyRole('BANKER', 'ADMIN') or (hasRole('SERVICE') and @eligibilityCallers.allowed(authentication))";

    private final LoanEligibilityUseCase useCase;

    public LoanEligibilityController(LoanEligibilityUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    @PreAuthorize(CALLERS)
    public ResponseEntity<LoanEligibilityResponse> check(@Valid @RequestBody LoanEligibilityRequest request) {
        LoanEligibilityCommand question = request.toCommand();
        return ResponseEntity.ok(LoanEligibilityResponse.of(question, useCase.check(question)));
    }
}
