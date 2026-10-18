package com.bank.risk.domain.service;

import com.bank.risk.domain.AttestationSource;
import com.bank.risk.domain.CounterpartyCountry;
import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.RiskAssessedEvent;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.port.in.TransactionAssessmentCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rsk-policy-v4: the same thresholds and patterns as v3, now applied to facts
 * risk resolved itself (v2 API). The decision records RISK_RESOLVED, the
 * customer and the counterparty country.
 */
class RiskPolicyServiceResolvedFactsTest {

    private final RiskPolicyService service = new RiskPolicyService();

    private static TransactionAssessmentCommand request(String amount, PaymentType type) {
        return new TransactionAssessmentCommand("PAY-V2-1", "CUST-9", new BigDecimal(amount), "USD", type,
                "IR580540105180021273113007", "svc-pay-initiation-settlement");
    }

    @Test
    void theRuleSetVersionIsBumpedForRiskResolvedFacts() {
        assertThat(RiskPolicyService.RULE_SET_VERSION).isEqualTo("rsk-policy-v4");
    }

    @Test
    void resolvedFactsScoreLikeCallerStatedOnesAndAreRecordedAsRiskResolved() {
        RiskAssessment assessment = service.evaluate(request("12000.50", PaymentType.TRANSFER), CounterpartyCountry.of("IR"), true, 40);

        assertThat(assessment.getScore()).isEqualTo(30 + 40 + 15);
        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(assessment.getReasons()).containsExactly("HIGH_AMOUNT", "HIGH_RISK_COUNTRY", "MEDIUM_VELOCITY");
        assertThat(assessment.getAttestationSource()).isEqualTo(AttestationSource.RISK_RESOLVED);
        assertThat(assessment.getAttestedBy()).as("who asked is still recorded").isEqualTo("svc-pay-initiation-settlement");
        assertThat(assessment.getCustomerId()).isEqualTo("CUST-9");
        assertThat(assessment.getCounterpartyCountry()).isEqualTo("IR");
        assertThat(assessment.isHighRiskCountry()).isTrue();
        assertThat(assessment.getVelocityScore()).isEqualTo(40);
        assertThat(assessment.getPaymentType()).isEqualTo(PaymentType.TRANSFER);
        assertThat(assessment.getRuleSetVersion()).isEqualTo("rsk-policy-v4");
    }

    @Test
    void anUnknownCounterpartyCountryIsStoredAsNullAndFlaggedHighRisk() {
        RiskAssessment assessment = service.evaluate(request("100.00", PaymentType.TRANSFER), CounterpartyCountry.UNKNOWN, true, 0);

        assertThat(assessment.getCounterpartyCountry()).isNull();
        assertThat(assessment.isHighRiskCountry()).isTrue();
        assertThat(assessment.getReasons()).containsExactly("HIGH_RISK_COUNTRY");
        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    void theSuspiciousPatternsStillDecideBlockOnResolvedFacts() {
        RiskAssessment mobile = service.evaluate(request("5000.01", PaymentType.MOBILE_PAYMENT), CounterpartyCountry.of("GB"), false, 0);

        assertThat(mobile.getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(mobile.getReasons()).containsExactly(RiskPolicyService.SUSPICIOUS_MOBILE_AMOUNT);
    }

    @Test
    void aResolvedDecisionRaisesTheAssessedEventOnceWithItsAttestationSource() {
        RiskAssessment assessment = service.evaluate(request("100.00", PaymentType.TRANSFER), CounterpartyCountry.of("GB"), false, 0);

        assertThat(assessment.getDomainEvents()).hasSize(1);
        RiskAssessedEvent event = (RiskAssessedEvent) assessment.getDomainEvents().getFirst();
        assertThat(event.attestationSource()).isEqualTo(AttestationSource.RISK_RESOLVED);
        assertThat(event.transactionId()).isEqualTo("PAY-V2-1");
    }
}
