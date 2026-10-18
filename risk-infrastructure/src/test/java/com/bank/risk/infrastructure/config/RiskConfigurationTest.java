package com.bank.risk.infrastructure.config;

import com.bank.risk.domain.port.in.LoanEligibilityUseCase;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import com.bank.risk.domain.port.in.TransactionAssessmentUseCase;
import com.bank.risk.domain.port.out.AssessmentHistory;
import com.bank.risk.domain.port.out.CustomerCreditProfilePort;
import com.bank.risk.domain.port.out.HighRiskCountryList;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.domain.service.RiskPolicyService;
import com.bank.risk.infrastructure.transaction.TransactionalRiskAssessmentUseCase;
import com.bank.risk.infrastructure.transaction.TransactionalTransactionAssessmentUseCase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RiskConfigurationTest {

    private final RiskConfiguration configuration = new RiskConfiguration();

    @Test
    void shouldCreateRiskPolicyServiceBean() {
        assertThat(configuration.riskPolicyService()).isNotNull();
    }

    @Test
    void theUseCaseBeanIsTheTransactionalBoundaryAroundTheService() {
        RiskPolicyService policyService = configuration.riskPolicyService();

        RiskAssessmentUseCase useCase = configuration.riskAssessmentUseCase(policyService,
            mock(RiskAssessmentRepository.class), mock(RiskEventPublisher.class), mock(PlatformTransactionManager.class));

        assertThat(useCase).isInstanceOf(TransactionalRiskAssessmentUseCase.class);
    }

    /** v2 reads the list and the history and writes the decision and its outbox row in one transaction. */
    @Test
    void theV2UseCaseBeanIsTheTransactionalBoundaryAroundTheService() {
        TransactionAssessmentUseCase useCase = configuration.transactionAssessmentUseCase(configuration.riskPolicyService(),
            mock(HighRiskCountryList.class), mock(AssessmentHistory.class), mock(RiskAssessmentRepository.class),
            mock(RiskEventPublisher.class), mock(PlatformTransactionManager.class), "AE", Duration.ofDays(35), Duration.ofHours(24));

        assertThat(useCase).isInstanceOf(TransactionalTransactionAssessmentUseCase.class);
    }

    @Test
    void theEligibilityUseCaseBeanWrapsTheCustomerPort() {
        LoanEligibilityUseCase useCase = configuration.loanEligibilityUseCase(mock(CustomerCreditProfilePort.class));

        assertThat(useCase).isNotNull();
    }
}
