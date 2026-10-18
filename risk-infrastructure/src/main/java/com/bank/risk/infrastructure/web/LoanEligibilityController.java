package com.bank.risk.infrastructure.web;

import com.bank.risk.domain.LoanEligibility;
import com.bank.risk.domain.port.in.LoanEligibilityCommand;
import com.bank.risk.domain.port.in.LoanEligibilityUseCase;
import com.bank.risk.infrastructure.web.dto.LoanEligibilityRequest;
import com.bank.risk.infrastructure.web.dto.LoanEligibilityResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/risk/loan-eligibility ("Risk decides"): the loan service asks
 * whether a customer may borrow an amount; risk reads the customer's credit
 * standing and applies its rule. Callers: SERVICE clients on
 * SERVICE_CALLERS_ELIGIBILITY (default svc-ln-loan-lifecycle) only; no staff
 * role opens it (repeated amounts would reveal a customer's credit band).
 * Nothing is stored here; every decision is logged with the caller's client,
 * the customer id, the decision, the reason and the interaction id, never the
 * score or the amount, and never as a metric tag.
 */
@RestController
@RequestMapping("/api/v1/risk/loan-eligibility")
public class LoanEligibilityController {
    static final String CALLERS = "hasRole('SERVICE') and @eligibilityCallers.allowed(authentication)";
    private static final Logger log = LoggerFactory.getLogger(LoanEligibilityController.class);

    private final LoanEligibilityUseCase useCase;

    public LoanEligibilityController(LoanEligibilityUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    @PreAuthorize(CALLERS)
    public ResponseEntity<LoanEligibilityResponse> check(@Valid @RequestBody LoanEligibilityRequest request, Authentication caller) {
        LoanEligibilityCommand question = request.toCommand();
        LoanEligibility answer = useCase.check(question);
        log.info("loan eligibility decided caller={} customerId={} decision={} reason={} interactionId={}",
                CallerAttestation.attestedBy(caller), question.customerId(), answer.decision(), answer.reason(),
                MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.ok(LoanEligibilityResponse.of(question, answer));
    }
}
