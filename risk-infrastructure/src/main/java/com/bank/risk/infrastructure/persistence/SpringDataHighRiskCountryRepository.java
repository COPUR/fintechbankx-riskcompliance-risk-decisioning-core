package com.bank.risk.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataHighRiskCountryRepository extends JpaRepository<HighRiskCountryJpaEntity, String> {
}
