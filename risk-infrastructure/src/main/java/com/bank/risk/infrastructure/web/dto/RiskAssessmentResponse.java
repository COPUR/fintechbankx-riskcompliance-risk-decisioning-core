package com.bank.risk.infrastructure.web.dto;

import com.bank.risk.domain.RiskAssessment;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record RiskAssessmentResponse(
        String assessmentId,
        String transactionId,
        int score,
        String decision,
        List<String> reasons,
        Instant assessedAt,
        String attestationSource,
        // Absent, not null, for decisions stored before the attester was recorded.
        @JsonInclude(JsonInclude.Include.NON_NULL) String attestedBy,
        String ruleSetVersion
) {
    public static RiskAssessmentResponse from(RiskAssessment assessment) {
        return new RiskAssessmentResponse(
                assessment.getId().getValue(),
                assessment.getTransactionId(),
                assessment.getScore(),
                assessment.getDecision().name(),
                assessment.getReasons(),
                assessment.getAssessedAt(),
                assessment.getAttestationSource().name(),
                assessment.getAttestedBy(),
                assessment.getRuleSetVersion()
        );
    }
}
