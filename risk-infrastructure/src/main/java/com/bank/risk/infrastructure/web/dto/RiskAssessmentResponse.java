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
        String ruleSetVersion,
        // v2 (RISK_RESOLVED) decisions only: absent on caller-attested ones.
        @JsonInclude(JsonInclude.Include.NON_NULL) String customerId,
        // v2, when the counterparty account resolved to a country; absent when unresolved (decided as high risk).
        @JsonInclude(JsonInclude.Include.NON_NULL) String counterpartyCountry
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
                assessment.getRuleSetVersion(),
                assessment.getCustomerId(),
                assessment.getCounterpartyCountry()
        );
    }
}
