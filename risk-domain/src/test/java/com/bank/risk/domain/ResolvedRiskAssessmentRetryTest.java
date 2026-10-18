package com.bank.risk.domain;

import com.bank.risk.domain.port.in.TransactionAssessmentCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A v2 retry repeats the caller's inputs (transactionId, customerId, amount by
 * value, currency, paymentType and the counterparty's country, resolved from
 * the account). highRiskCountry and velocityScore are risk's own facts: the
 * velocity moves with time, so they are recorded with the first decision and
 * never compared on a retry.
 */
class ResolvedRiskAssessmentRetryTest {

    private static TransactionAssessmentCommand request(String customer, String amount, String currency, PaymentType type, String account) {
        return new TransactionAssessmentCommand("PAY-R-1", customer, new BigDecimal(amount), currency, type, account, "svc-pay-initiation-settlement");
    }

    private static final TransactionAssessmentCommand FIRST = request("CUST-1", "100.00", "USD", PaymentType.TRANSFER, "GB82WEST12345698765432");

    private static RiskAssessment decided() {
        return RiskAssessment.resolve(FIRST, CounterpartyCountry.of("GB"), false, 40, 15, RiskDecision.ALLOW,
                List.of("MEDIUM_VELOCITY"), "rsk-policy-v4");
    }

    @Test
    void aFaithfulRetryGetsTheStoredDecisionWhateverTheVelocityIsNow() {
        RiskAssessment stored = decided();

        RiskAssessment answer = stored.answerRetry(request("CUST-1", "100.0", "USD", PaymentType.TRANSFER, "GB82 WEST 1234 5698 7654 32"),
                CounterpartyCountry.of("GB"));

        assertThat(answer).isSameAs(stored);
        assertThat(answer.getVelocityScore()).as("the first decision's velocity is kept").isEqualTo(40);
    }

    @Test
    void aRetryForAnotherCustomerAmountTypeOrCountryIsRefused() {
        RiskAssessment stored = decided();

        assertThatThrownBy(() -> stored.answerRetry(request("CUST-2", "100.00", "USD", PaymentType.TRANSFER, "GB82WEST12345698765432"), CounterpartyCountry.of("GB")))
            .isInstanceOf(TransactionAlreadyAssessedException.class);
        assertThatThrownBy(() -> stored.answerRetry(request("CUST-1", "100.01", "USD", PaymentType.TRANSFER, "GB82WEST12345698765432"), CounterpartyCountry.of("GB")))
            .isInstanceOf(TransactionAlreadyAssessedException.class);
        assertThatThrownBy(() -> stored.answerRetry(request("CUST-1", "100.00", "USD", PaymentType.MOBILE_PAYMENT, "GB82WEST12345698765432"), CounterpartyCountry.of("GB")))
            .isInstanceOf(TransactionAlreadyAssessedException.class);
        assertThatThrownBy(() -> stored.answerRetry(request("CUST-1", "100.00", "USD", PaymentType.TRANSFER, "DE89370400440532013000"), CounterpartyCountry.of("DE")))
            .isInstanceOf(TransactionAlreadyAssessedException.class);
        assertThatThrownBy(() -> stored.answerRetry(FIRST, CounterpartyCountry.UNKNOWN))
            .as("a country that can no longer be resolved is a different question")
            .isInstanceOf(TransactionAlreadyAssessedException.class);
    }

    @Test
    void aDecisionOnAnUnknownCountryIsRepeatedOnlyWithAnUnknownCountry() {
        TransactionAssessmentCommand unknown = request("CUST-1", "100.00", "USD", PaymentType.TRANSFER, "wallet:abc");
        RiskAssessment stored = RiskAssessment.resolve(unknown, CounterpartyCountry.UNKNOWN, true, 0, 40, RiskDecision.ALLOW,
                List.of("HIGH_RISK_COUNTRY"), "rsk-policy-v4");

        assertThat(stored.answerRetry(unknown, CounterpartyCountry.UNKNOWN)).isSameAs(stored);
        assertThatThrownBy(() -> stored.answerRetry(unknown, CounterpartyCountry.of("GB")))
            .isInstanceOf(TransactionAlreadyAssessedException.class);
    }

    /** A v1 decision (caller-attested, no customer) under the same id is never answered to a v2 retry. */
    @Test
    void aCallerAttestedDecisionDoesNotAnswerAResolvedRetry() {
        RiskAssessment v1 = RiskAssessment.create(new com.bank.risk.domain.port.in.RiskEvaluationCommand("PAY-R-1",
                new BigDecimal("100.00"), "USD", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER),
                0, RiskDecision.ALLOW, List.of(), "rsk-policy-v3");

        assertThat(v1.getCustomerId()).isNull();
        assertThatThrownBy(() -> v1.answerRetry(FIRST, CounterpartyCountry.of("GB")))
            .isInstanceOf(TransactionAlreadyAssessedException.class);
    }

    @Test
    void aResolvedDecisionRehydratesWithItsCustomerAndCountry() {
        RiskAssessment stored = RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-R-1"), "PAY-R-1", new BigDecimal("100.00"), "USD", false, 40, 15,
                RiskDecision.ALLOW, List.of("MEDIUM_VELOCITY"), Instant.parse("2026-10-18T10:00:00Z"),
                AttestationSource.RISK_RESOLVED, "svc-pay-initiation-settlement", "rsk-policy-v4", PaymentType.TRANSFER,
                "CUST-1", "GB"));

        assertThat(stored.getCustomerId()).isEqualTo("CUST-1");
        assertThat(stored.getCounterpartyCountry()).isEqualTo("GB");
        assertThat(stored.getAttestationSource()).isEqualTo(AttestationSource.RISK_RESOLVED);
        assertThat(stored.answerRetry(FIRST, CounterpartyCountry.of("GB"))).isSameAs(stored);
        assertThat(stored.getDomainEvents()).isEmpty();
    }

    @Test
    void aRiskResolvedDecisionAlwaysNamesItsCustomer() {
        assertThatThrownBy(() -> RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-R-2"), "PAY-R-2", new BigDecimal("1.00"), "USD", false, 0, 0,
                RiskDecision.ALLOW, List.of(), Instant.parse("2026-10-18T10:00:00Z"),
                AttestationSource.RISK_RESOLVED, "svc", "rsk-policy-v4", PaymentType.TRANSFER, null, "GB")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("customerId");
    }
}
