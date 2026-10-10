package com.bank.risk.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataRiskAssessmentRepository extends JpaRepository<RiskAssessmentJpaEntity, String> {

    Optional<RiskAssessmentJpaEntity> findByTransactionId(String transactionId);
}
