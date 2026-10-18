package com.bank.risk.application;

import com.bank.risk.domain.AttestationSource;
import com.bank.risk.domain.CounterpartyCountry;
import com.bank.risk.domain.HighRiskCountries;
import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.RiskSourceUnavailableException;
import com.bank.risk.domain.TransactionAlreadyAssessedException;
import com.bank.risk.domain.port.in.TransactionAssessmentCommand;
import com.bank.risk.domain.port.out.AssessmentHistory;
import com.bank.risk.domain.port.out.HighRiskCountryList;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.domain.service.CounterpartyCountryResolver;
import com.bank.risk.domain.service.HighRiskCountryPolicy;
import com.bank.risk.domain.service.RiskPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * POST /api/v2/risk/assess orchestration: the facts come from risk's own
 * ports (the high-risk country list, the customer's assessment history), the
 * policy decides, the decision is saved and its event published once.
 */
@ExtendWith(MockitoExtension.class)
class TransactionAssessmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(24);

    @Mock private HighRiskCountryList highRiskCountries;
    @Mock private AssessmentHistory history;
    @Mock private RiskAssessmentRepository repository;
    @Mock private RiskEventPublisher eventPublisher;

    private TransactionAssessmentService service;

    @BeforeEach
    void setUp() {
        service = new TransactionAssessmentService(new RiskPolicyService(), new CounterpartyCountryResolver("AE"),
                new HighRiskCountryPolicy(Duration.ofDays(35), Clock.fixed(NOW, ZoneOffset.UTC)), WINDOW,
                highRiskCountries, history, repository, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TransactionAssessmentCommand request(String id, String account) {
        return new TransactionAssessmentCommand(id, "CUST-1", new BigDecimal("12000.50"), "USD", PaymentType.TRANSFER, account,
                "svc-pay-initiation-settlement");
    }

    private static Optional<HighRiskCountries> freshList(String... codes) {
        return Optional.of(new HighRiskCountries(Set.of(codes), "FATF", "2026-06", NOW.minus(Duration.ofDays(2))));
    }

    @Test
    void resolvesCountryAndVelocityFromItsOwnSourcesThenSavesAndPublishesOnce() {
        when(repository.findByTransactionId("PAY-1")).thenReturn(Optional.empty());
        when(highRiskCountries.current()).thenReturn(freshList("IR", "KP"));
        when(history.countAssessments("CUST-1", NOW.minus(WINDOW))).thenReturn(4);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RiskAssessment decision = service.assess(request("PAY-1", "IR580540105180021273113007"));

        assertThat(decision.isHighRiskCountry()).isTrue();
        assertThat(decision.getCounterpartyCountry()).isEqualTo("IR");
        assertThat(decision.getVelocityScore()).as("4 prior assessments in the window map to 40").isEqualTo(40);
        assertThat(decision.getReasons()).containsExactly("HIGH_AMOUNT", "HIGH_RISK_COUNTRY", "MEDIUM_VELOCITY");
        assertThat(decision.getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(decision.getAttestationSource()).isEqualTo(AttestationSource.RISK_RESOLVED);
        assertThat(decision.getCustomerId()).isEqualTo("CUST-1");

        InOrder order = inOrder(repository, eventPublisher);
        order.verify(repository).save(decision);
        ArgumentCaptor<List<com.bank.risk.domain.RiskDomainEvent>> events = ArgumentCaptor.forClass(List.class);
        order.verify(eventPublisher).publish(events.capture());
        assertThat(events.getValue()).hasSize(1);
        assertThat(decision.getDomainEvents()).isEmpty();
    }

    @Test
    void aCounterpartyOutsideTheListWithNoHistoryIsAllowed() {
        when(repository.findByTransactionId("PAY-2")).thenReturn(Optional.empty());
        when(highRiskCountries.current()).thenReturn(freshList("IR"));
        when(history.countAssessments("CUST-1", NOW.minus(WINDOW))).thenReturn(0);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RiskAssessment decision = service.assess(request("PAY-2", "GB82WEST12345698765432"));

        assertThat(decision.isHighRiskCountry()).isFalse();
        assertThat(decision.getCounterpartyCountry()).isEqualTo("GB");
        assertThat(decision.getVelocityScore()).isZero();
        assertThat(decision.getReasons()).containsExactly("HIGH_AMOUNT");
        assertThat(decision.getDecision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    void anInternalAccountIsTheHomeCountryAndAnUnresolvableAccountIsHighRisk() {
        when(repository.findByTransactionId(anyString())).thenReturn(Optional.empty());
        when(highRiskCountries.current()).thenReturn(freshList("IR"));
        when(history.countAssessments(anyString(), any())).thenReturn(0);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RiskAssessment internal = service.assess(request("PAY-3", "ACC-9b2f6c1e-4d3a-4f8b-9c7d-2e1f0a9b8c7d"));
        RiskAssessment unresolvable = service.assess(request("PAY-4", "wallet:xyz"));

        assertThat(internal.getCounterpartyCountry()).isEqualTo("AE");
        assertThat(internal.isHighRiskCountry()).isFalse();
        assertThat(unresolvable.getCounterpartyCountry()).isNull();
        assertThat(unresolvable.isHighRiskCountry()).isTrue();
        assertThat(unresolvable.getReasons()).contains("HIGH_RISK_COUNTRY");
    }

    @Test
    void anEmptyOrStaleListIsRefusedBeforeAnythingIsSavedOrPublished() {
        when(repository.findByTransactionId("PAY-5")).thenReturn(Optional.empty());
        when(highRiskCountries.current()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assess(request("PAY-5", "GB82WEST12345698765432")))
            .isInstanceOf(RiskSourceUnavailableException.class);

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher, history);
    }

    @Test
    void aFaithfulRetryReturnsTheStoredDecisionWithoutReadingAnySource() {
        TransactionAssessmentCommand first = request("PAY-6", "GB82WEST12345698765432");
        RiskAssessment stored = RiskAssessment.resolve(first, CounterpartyCountry.of("GB"), false, 70, 60, RiskDecision.REVIEW,
                List.of("HIGH_AMOUNT", "HIGH_VELOCITY"), "rsk-policy-v4");
        stored.clearDomainEvents();
        when(repository.findByTransactionId("PAY-6")).thenReturn(Optional.of(stored));

        RiskAssessment answer = service.assess(request("PAY-6", "GB82 WEST 1234 5698 7654 32"));

        assertThat(answer).isSameAs(stored);
        verifyNoInteractions(highRiskCountries, history, eventPublisher);
        verify(repository, never()).save(any());
    }

    @Test
    void aReusedTransactionIdWithAnotherCounterpartyIsAConflict() {
        TransactionAssessmentCommand first = request("PAY-7", "GB82WEST12345698765432");
        RiskAssessment stored = RiskAssessment.resolve(first, CounterpartyCountry.of("GB"), false, 0, 30, RiskDecision.ALLOW,
                List.of("HIGH_AMOUNT"), "rsk-policy-v4");
        when(repository.findByTransactionId("PAY-7")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.assess(request("PAY-7", "DE89370400440532013000")))
            .isInstanceOf(TransactionAlreadyAssessedException.class)
            .hasMessageContaining("PAY-7");
        verifyNoInteractions(highRiskCountries, history, eventPublisher);
    }

    @Test
    void nothingIsPublishedWhenTheSaveFails() {
        when(repository.findByTransactionId("PAY-8")).thenReturn(Optional.empty());
        when(highRiskCountries.current()).thenReturn(freshList("IR"));
        when(history.countAssessments(anyString(), any())).thenReturn(0);
        when(repository.save(any())).thenThrow(new IllegalStateException("unique transaction_id"));

        assertThatThrownBy(() -> service.assess(request("PAY-8", "GB82WEST12345698765432")))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(eventPublisher);
    }
}
