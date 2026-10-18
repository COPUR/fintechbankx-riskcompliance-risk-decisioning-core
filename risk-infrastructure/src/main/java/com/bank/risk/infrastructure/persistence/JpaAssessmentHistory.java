package com.bank.risk.infrastructure.persistence;

import com.bank.risk.domain.port.out.AssessmentHistory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Out-port adapter for {@link AssessmentHistory} over the decisions of record
 * (sc_rsk_decisioning.risk_assessment, V13 index on customer_id, assessed_at).
 * Only v2 decisions carry a customer, so v1 rows never count.
 */
@Repository
@Transactional(readOnly = true)
public class JpaAssessmentHistory implements AssessmentHistory {

    private final SpringDataRiskAssessmentRepository assessments;

    public JpaAssessmentHistory(SpringDataRiskAssessmentRepository assessments) {
        this.assessments = assessments;
    }

    @Override
    public int countAssessments(String customerId, Instant since) {
        return (int) Math.min(Integer.MAX_VALUE, assessments.countByCustomerIdAndAssessedAtGreaterThanEqual(customerId, since));
    }
}
