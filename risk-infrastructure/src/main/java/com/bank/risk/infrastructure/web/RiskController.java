package com.bank.risk.infrastructure.web;

import com.bank.risk.infrastructure.web.dto.EvaluateRiskRequest;
import com.bank.risk.infrastructure.web.dto.RiskAssessmentResponse;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Transaction risk decisions, v1. Called by payment services with a SERVICE-role
 * client-credentials token whose client (azp) is on SERVICE_CALLERS, and read
 * by bank staff.
 *
 * <p>POST /api/v1/risk/assess takes the risk facts from the caller and is
 * deprecated (screening-facts plan, 2026-10-18): risk resolves them itself on
 * POST /api/v2/risk/assess. It answers only while RISK_V1_CALLER_FACTS_ENABLED
 * is true, which keeps payments working until they move; off (the default) it
 * is 410 API_VERSION_GONE with a body that names v2. Reading a stored decision
 * (GET /api/v1/risk/assessments/{transactionId}) is not deprecated.
 */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {
    static final String CALLERS = "hasAnyRole('BANKER', 'ADMIN') or (hasRole('SERVICE') and @serviceCallers.allowed(authentication))";
    static final String V1_GONE = "API_VERSION_GONE";
    static final String V1_GONE_MESSAGE = "POST /api/v1/risk/assess is retired: risk resolves highRiskCountry and velocityScore"
            + " itself. Call POST /api/v2/risk/assess with transactionId, customerId, amount, currency, paymentType and counterpartyAccount";

    private final RiskAssessmentUseCase service;
    private final boolean v1CallerFactsEnabled;

    /** Receives the transactional use case from RiskConfiguration, never the bare service. */
    public RiskController(RiskAssessmentUseCase service,
                          @Value("${risk.v1.caller-facts-enabled:false}") boolean v1CallerFactsEnabled) {
        this.service = service;
        this.v1CallerFactsEnabled = v1CallerFactsEnabled;
    }

    @PostMapping("/assess")
    @PreAuthorize(CALLERS)
    public ResponseEntity<?> assess(@Valid @RequestBody EvaluateRiskRequest request, Authentication caller) {
        if (!v1CallerFactsEnabled) {
            return ApiExceptionHandler.error(HttpStatus.GONE, V1_GONE, V1_GONE_MESSAGE);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RiskAssessmentResponse.from(service.assess(request.toCommand(CallerAttestation.attestedBy(caller)))));
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
