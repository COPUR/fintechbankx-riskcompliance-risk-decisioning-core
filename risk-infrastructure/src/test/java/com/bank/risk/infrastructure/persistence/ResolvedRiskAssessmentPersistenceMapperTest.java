package com.bank.risk.infrastructure.persistence;

import com.bank.risk.domain.AttestationSource;
import com.bank.risk.domain.CounterpartyCountry;
import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.port.in.TransactionAssessmentCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V13: a risk-resolved decision keeps its customer and counterparty country across the row. */
class ResolvedRiskAssessmentPersistenceMapperTest {

    @Test
    void roundTripKeepsTheCustomerAndTheCounterpartyCountry() {
        TransactionAssessmentCommand request = new TransactionAssessmentCommand("PAY-MAP-2", "CUST-MAP", new BigDecimal("100.00"),
                "USD", PaymentType.TRANSFER, "IR580540105180021273113007", "svc-pay-initiation-settlement");
        RiskAssessment decided = RiskAssessment.resolve(request, CounterpartyCountry.of("IR"), true, 40, 55, RiskDecision.REVIEW,
                List.of("HIGH_RISK_COUNTRY", "MEDIUM_VELOCITY"), "rsk-policy-v4");

        RiskAssessmentJpaEntity row = RiskAssessmentPersistenceMapper.toEntity(decided);
        RiskAssessment loaded = RiskAssessmentPersistenceMapper.toDomain(row);

        assertThat(row.getCustomerId()).isEqualTo("CUST-MAP");
        assertThat(row.getCounterpartyCountry()).isEqualTo("IR");
        assertThat(row.getAttestationSource()).isEqualTo("RISK_RESOLVED");
        assertThat(loaded.getCustomerId()).isEqualTo("CUST-MAP");
        assertThat(loaded.getCounterpartyCountry()).isEqualTo("IR");
        assertThat(loaded.getAttestationSource()).isEqualTo(AttestationSource.RISK_RESOLVED);
        assertThat(loaded.isHighRiskCountry()).isTrue();
        assertThat(loaded.getVelocityScore()).isEqualTo(40);
    }

    @Test
    void anUnknownCountryIsStoredAsNull() {
        TransactionAssessmentCommand request = new TransactionAssessmentCommand("PAY-MAP-3", "CUST-MAP", new BigDecimal("100.00"),
                "USD", PaymentType.TRANSFER, "wallet:abc", "svc-pay-initiation-settlement");
        RiskAssessment decided = RiskAssessment.resolve(request, CounterpartyCountry.UNKNOWN, true, 0, 40, RiskDecision.ALLOW,
                List.of("HIGH_RISK_COUNTRY"), "rsk-policy-v4");

        RiskAssessmentJpaEntity row = RiskAssessmentPersistenceMapper.toEntity(decided);

        assertThat(row.getCounterpartyCountry()).isNull();
        assertThat(RiskAssessmentPersistenceMapper.toDomain(row).getCounterpartyCountry()).isNull();
    }
}
