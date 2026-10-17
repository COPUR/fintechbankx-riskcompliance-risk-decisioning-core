package com.bank.risk.infrastructure.persistence;

import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Out-port adapter for {@link RiskAssessmentRepository} over the service's own
 * schema (sc_rsk_decisioning). The unique transaction_id index makes the
 * first of two concurrent assessments of one transaction win; the other gets
 * a DataIntegrityViolationException and the caller retries to read it.
 */
@Repository
@Transactional
public class JpaRiskAssessmentRepository implements RiskAssessmentRepository {

    private final SpringDataRiskAssessmentRepository assessments;

    public JpaRiskAssessmentRepository(SpringDataRiskAssessmentRepository assessments) {
        this.assessments = assessments;
    }

    @Override
    public RiskAssessment save(RiskAssessment assessment) {
        assessments.saveAndFlush(RiskAssessmentPersistenceMapper.toEntity(assessment));
        return assessment;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RiskAssessment> findByTransactionId(String transactionId) {
        return assessments.findByTransactionId(transactionId).map(RiskAssessmentPersistenceMapper::toDomain);
    }
}
