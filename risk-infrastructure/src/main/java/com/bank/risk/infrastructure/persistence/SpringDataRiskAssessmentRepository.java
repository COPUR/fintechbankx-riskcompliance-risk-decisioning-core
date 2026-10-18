package com.bank.risk.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

interface SpringDataRiskAssessmentRepository extends JpaRepository<RiskAssessmentJpaEntity, String> {

    Optional<RiskAssessmentJpaEntity> findByTransactionId(String transactionId);

    /** Velocity (V13 index): the customer's decisions of record decided at or after {@code since}. */
    long countByCustomerIdAndAssessedAtGreaterThanEqual(String customerId, Instant since);
}
