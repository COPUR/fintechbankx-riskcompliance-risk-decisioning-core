package com.bank.risk.infrastructure.web;

import com.bank.risk.domain.port.in.TransactionAssessmentUseCase;
import com.bank.risk.infrastructure.web.dto.AssessTransactionRequest;
import com.bank.risk.infrastructure.web.dto.RiskAssessmentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v2/risk/assess (screening-facts plan): payments send ids and
 * amounts; risk resolves the high-risk country and the velocity itself. Same
 * callers as v1: SERVICE clients on SERVICE_CALLERS, and bank staff.
 */
@RestController
@RequestMapping("/api/v2/risk")
public class TransactionAssessmentController {

    private final TransactionAssessmentUseCase useCase;

    /** Receives the transactional use case from RiskConfiguration, never the bare service. */
    public TransactionAssessmentController(TransactionAssessmentUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/assess")
    @PreAuthorize(RiskController.CALLERS)
    public ResponseEntity<RiskAssessmentResponse> assess(@Valid @RequestBody AssessTransactionRequest request,
                                                         Authentication caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RiskAssessmentResponse.from(useCase.assess(request.toCommand(CallerAttestation.attestedBy(caller)))));
    }
}
