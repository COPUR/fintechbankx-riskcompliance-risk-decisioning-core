package com.bank.risk.infrastructure.config;

import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.domain.service.RiskPolicyService;
import com.bank.risk.infrastructure.transaction.TransactionalRiskAssessmentUseCase;
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
}
