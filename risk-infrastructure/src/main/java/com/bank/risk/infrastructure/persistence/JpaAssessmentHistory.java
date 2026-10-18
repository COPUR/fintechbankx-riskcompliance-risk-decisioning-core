package com.bank.risk.infrastructure.persistence;

import com.bank.risk.domain.port.out.AssessmentHistory;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Out-port adapter for {@link AssessmentHistory} over the decisions of record
 * (sc_rsk_decisioning.risk_assessment, V13 index on customer_id, assessed_at).
 * Only v2 decisions carry a customer, so v1 rows never count.
 * <p>
 * The count runs under a transaction-scoped advisory lock keyed on the customer
 * ({@code pg_advisory_xact_lock(hashtext(customerId))}), inside the same
 * transaction as the insert that follows (TransactionalTransactionAssessmentUseCase),
 * so N payments fired at once for one customer are counted one after the
 * other and see 0, 1, ..., N-1 prior decisions, never the same count twice.
 * The lock is released with the transaction; it never leaves the database.
 */
@Repository
@Transactional
public class JpaAssessmentHistory implements AssessmentHistory {

    private final SpringDataRiskAssessmentRepository assessments;
    private final EntityManager entityManager;

    public JpaAssessmentHistory(SpringDataRiskAssessmentRepository assessments, EntityManager entityManager) {
        this.assessments = assessments;
        this.entityManager = entityManager;
    }

    @Override
    public int countAssessments(String customerId, Instant since) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(:customerId)) is null")
                .setParameter("customerId", customerId)
                .getSingleResult();
        return (int) Math.min(Integer.MAX_VALUE, assessments.countByCustomerIdAndAssessedAtGreaterThanEqual(customerId, since));
    }
}
