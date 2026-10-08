package com.bank.risk.infrastructure.web;

import com.bank.risk.infrastructure.web.dto.EvaluateRiskRequest;
import com.bank.risk.infrastructure.web.dto.RiskAssessmentResponse;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Transaction risk decisions. Called by payment services with a SERVICE-role
 * client-credentials token whose client (azp) is on SERVICE_CALLERS, and read
 * by bank staff.
 */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {
    static final String CALLERS = "hasAnyRole('BANKER', 'ADMIN') or (hasRole('SERVICE') and @serviceCallers.allowed(authentication))";

    private final RiskAssessmentUseCase service;

    /** Receives the transactional use case from RiskConfiguration, never the bare service. */
    public RiskController(RiskAssessmentUseCase service) {
        this.service = service;
    }

    @PostMapping("/assess")
    @PreAuthorize(CALLERS)
    public ResponseEntity<RiskAssessmentResponse> assess(@Valid @RequestBody EvaluateRiskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RiskAssessmentResponse.from(service.assess(request.toCommand())));
    }

    @GetMapping("/assessments/{transactionId}")
    @PreAuthorize(CALLERS)
    public ResponseEntity<?> find(@PathVariable String transactionId) {
        return service.findByTransactionId(transactionId)
                .<ResponseEntity<?>>map(assessment -> ResponseEntity.ok(RiskAssessmentResponse.from(assessment)))
                .orElseGet(() -> ApiExceptionHandler.error(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND",
                        "No risk assessment for this transaction"));
    }
}
