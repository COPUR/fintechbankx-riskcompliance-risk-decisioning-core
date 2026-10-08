package com.bank.risk.infrastructure.persistence;

import com.bank.risk.domain.AttestationSource;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskAssessmentId;
import com.bank.risk.domain.RiskAssessmentSnapshot;
import com.bank.risk.domain.RiskDecision;

import java.util.List;

final class RiskAssessmentPersistenceMapper {

    private RiskAssessmentPersistenceMapper() {
    }

    static RiskAssessmentJpaEntity toEntity(RiskAssessment assessment) {
        return new RiskAssessmentJpaEntity(
                assessment.getId().getValue(),
                assessment.getTransactionId(),
                assessment.getAmount(),
                assessment.getCurrency(),
                assessment.isHighRiskCountry(),
                assessment.getVelocityScore(),
                assessment.getScore(),
                assessment.getDecision().name(),
                List.copyOf(assessment.getReasons()),
                assessment.getAssessedAt(),
                assessment.getAttestationSource().name(),
                assessment.getAttestedBy());
    }

    static RiskAssessment toDomain(RiskAssessmentJpaEntity row) {
        return RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of(row.getAssessmentId()),
                row.getTransactionId(),
                row.getAmount(),
                row.getCurrency(),
                row.isHighRiskCountry(),
                row.getVelocityScore(),
                row.getScore(),
                RiskDecision.valueOf(row.getDecision()),
                row.getReasons() == null ? List.of() : row.getReasons(),
                row.getAssessedAt(),
                AttestationSource.valueOf(row.getAttestationSource()),
                row.getAttestedBy()));
    }
}
