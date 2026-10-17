package com.bank.risk.application;

import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.RiskAssessedEvent;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.TransactionAlreadyAssessedException;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.domain.service.RiskPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskAssessmentServiceTest {

    @Mock
    private RiskPolicyService policyService;

    @Mock
    private RiskAssessmentRepository repository;

    @Mock
    private RiskEventPublisher eventPublisher;

    @InjectMocks
    private RiskAssessmentService service;

    @Test
    void shouldReturnExistingAssessmentWhenTransactionAlreadyAssessed() {
        RiskEvaluationCommand command = new RiskEvaluationCommand("TX-1", new BigDecimal("100"), "AED", false, 10, "svc-pay-initiation-settlement", PaymentType.TRANSFER);
        RiskAssessment existing = RiskAssessment.create(new RiskEvaluationCommand("TX-1", new BigDecimal("100.00"), "AED", false, 10, "svc-pay-initiation-settlement", PaymentType.TRANSFER), 10, RiskDecision.ALLOW, List.of(), "rsk-policy-v2");

        when(repository.findByTransactionId("TX-1")).thenReturn(Optional.of(existing));

        RiskAssessment result = service.assess(command);

        assertThat(result).isEqualTo(existing);
        verify(policyService, never()).evaluate(any());
        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void shouldEvaluateAndPersistWhenNoExistingAssessment() {
        RiskEvaluationCommand command = new RiskEvaluationCommand("TX-2", new BigDecimal("200"), "AED", true, 60, "svc-pay-initiation-settlement", PaymentType.TRANSFER);
        RiskAssessment assessed = RiskAssessment.create(new RiskEvaluationCommand("TX-2", new BigDecimal("200"), "AED", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER), 85, RiskDecision.BLOCK, List.of("HIGH_RISK_COUNTRY"), "rsk-policy-v2");

        when(repository.findByTransactionId("TX-2")).thenReturn(Optional.empty());
        when(policyService.evaluate(command)).thenReturn(assessed);
        when(repository.save(assessed)).thenReturn(assessed);

        RiskAssessment result = service.assess(command);

        assertThat(result).isEqualTo(assessed);
        verify(policyService).evaluate(command);
        verify(repository).save(assessed);
    }

    @Test
    void aNewAssessmentIsSavedThenItsAssessedEventPublishedOnce() {
        RiskEvaluationCommand command = new RiskEvaluationCommand("TX-5", new BigDecimal("12000.00"), "AED", false, 45, "svc-pay-initiation-settlement", PaymentType.TRANSFER);
        RiskAssessment assessed = RiskAssessment.create(new RiskEvaluationCommand("TX-5", new BigDecimal("12000.00"), "AED", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER), 45,
                RiskDecision.ALLOW, List.of("HIGH_AMOUNT", "MEDIUM_VELOCITY"), "rsk-policy-v2");
        RiskAssessedEvent raised = (RiskAssessedEvent) assessed.getDomainEvents().getFirst();
        when(repository.findByTransactionId("TX-5")).thenReturn(Optional.empty());
        when(policyService.evaluate(command)).thenReturn(assessed);
        when(repository.save(assessed)).thenReturn(assessed);

        service.assess(command);

        InOrder order = inOrder(repository, eventPublisher);
        order.verify(repository).save(assessed);
        order.verify(eventPublisher).publish(List.of(raised));
        verifyNoMoreInteractions(eventPublisher);
        assertThat(assessed.getDomainEvents()).isEmpty();
    }

    @Test
    void nothingIsPublishedWhenTheSaveFails() {
        RiskEvaluationCommand command = new RiskEvaluationCommand("TX-6", new BigDecimal("50.00"), "AED", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER);
        RiskAssessment assessed = RiskAssessment.create(new RiskEvaluationCommand("TX-6", new BigDecimal("50.00"), "AED", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER), 0, RiskDecision.ALLOW, List.of(), "rsk-policy-v2");
        when(repository.findByTransactionId("TX-6")).thenReturn(Optional.empty());
        when(policyService.evaluate(command)).thenReturn(assessed);
        when(repository.save(assessed)).thenThrow(new IllegalStateException("unique transaction_id"));

        assertThatThrownBy(() -> service.assess(command)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void findByTransactionIdShouldDelegateToRepository() {
        when(repository.findByTransactionId("TX-3")).thenReturn(Optional.empty());

        assertThat(service.findByTransactionId("TX-3")).isEmpty();
        verify(repository).findByTransactionId("TX-3");
    }

    @Test
    void shouldRefuseAReusedTransactionIdWithADifferentAmount() {
        RiskEvaluationCommand command = new RiskEvaluationCommand("TX-9", new BigDecimal("101"), "AED", false, 10, "svc-pay-initiation-settlement", PaymentType.TRANSFER);
        RiskAssessment existing = RiskAssessment.create(new RiskEvaluationCommand("TX-9", new BigDecimal("100"), "AED", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER), 10, RiskDecision.ALLOW, List.of(), "rsk-policy-v2");
        when(repository.findByTransactionId("TX-9")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.assess(command))
                .isInstanceOf(TransactionAlreadyAssessedException.class)
                .hasMessageContaining("TX-9");
        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aReusedTransactionIdWithTheHighRiskFlagFlippedIsRefused() {
        RiskAssessment existing = RiskAssessment.create(
                new RiskEvaluationCommand("TX-10", new BigDecimal("100.00"), "AED", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER), 0, RiskDecision.ALLOW, List.of(), "rsk-policy-v2");
        when(repository.findByTransactionId("TX-10")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.assess(new RiskEvaluationCommand("TX-10", new BigDecimal("100.00"), "AED", true, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER)))
                .isInstanceOf(TransactionAlreadyAssessedException.class)
                .hasMessageContaining("different inputs");
        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher, policyService);
    }
}
