package com.bank.risk.infrastructure.web;

import com.bank.risk.application.RiskAssessmentService;
import com.bank.risk.application.dto.EvaluateRiskRequest;
import com.bank.risk.application.dto.RiskAssessmentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Transaction risk decisions. Called by payment services with a SERVICE-role
 * client-credentials token, and read by bank staff.
 */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {
    private final RiskAssessmentService service;

    public RiskController(RiskAssessmentService service) {
        this.service = service;
    }

    @PostMapping("/assess")
    @PreAuthorize("hasAnyRole('SERVICE', 'BANKER', 'ADMIN')")
    public ResponseEntity<RiskAssessmentResponse> assess(@Valid @RequestBody EvaluateRiskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RiskAssessmentResponse.from(service.assess(request.toCommand())));
    }

    @GetMapping("/assessments/{transactionId}")
    @PreAuthorize("hasAnyRole('SERVICE', 'BANKER', 'ADMIN')")
    public ResponseEntity<?> find(@PathVariable String transactionId) {
        return service.findByTransactionId(transactionId)
                .<ResponseEntity<?>>map(assessment -> ResponseEntity.ok(RiskAssessmentResponse.from(assessment)))
                .orElseGet(() -> ApiExceptionHandler.error(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND",
                        "No risk assessment for this transaction"));
    }
}
