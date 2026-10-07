package com.bank.risk.infrastructure.persistence;

import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskAssessmentPersistenceMapperTest {

    @Test
    void roundTripKeepsTheDecisionOfRecord() {
        RiskAssessment decided = RiskAssessment.create("TX-MAP-1", new BigDecimal("60000.00"), "AED", 85,
                RiskDecision.BLOCK, List.of("HIGH_AMOUNT", "VERY_HIGH_AMOUNT", "HIGH_VELOCITY"));

        RiskAssessmentJpaEntity row = RiskAssessmentPersistenceMapper.toEntity(decided);
        RiskAssessment loaded = RiskAssessmentPersistenceMapper.toDomain(row);

        assertThat(row.getDecision()).isEqualTo("BLOCK");
        assertThat(loaded.getId()).isEqualTo(decided.getId());
        assertThat(loaded.getTransactionId()).isEqualTo("TX-MAP-1");
        assertThat(loaded.getAmount()).isEqualByComparingTo("60000.00");
        assertThat(loaded.getScore()).isEqualTo(85);
        assertThat(loaded.getReasons()).containsExactly("HIGH_AMOUNT", "VERY_HIGH_AMOUNT", "HIGH_VELOCITY");
        assertThat(loaded.getAssessedAt()).isEqualTo(decided.getAssessedAt());
    }
}
