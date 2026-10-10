package com.bank.risk.infrastructure.config;

import com.bank.risk.application.RiskAssessmentService;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.domain.service.RiskPolicyService;
import com.bank.risk.infrastructure.transaction.TransactionalRiskAssessmentUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class RiskConfiguration {

    @Bean
    RiskPolicyService riskPolicyService() {
        return new RiskPolicyService();
    }

    /**
     * The only {@link RiskAssessmentUseCase} bean: the application service
     * wrapped in its transaction boundary, so callers cannot bypass it.
     */
    @Bean
    RiskAssessmentUseCase riskAssessmentUseCase(
            RiskPolicyService riskPolicyService,
            RiskAssessmentRepository riskAssessmentRepository,
            RiskEventPublisher riskEventPublisher,
            PlatformTransactionManager transactionManager
    ) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return new TransactionalRiskAssessmentUseCase(
                new RiskAssessmentService(riskPolicyService, riskAssessmentRepository, riskEventPublisher),
                new TransactionTemplate(transactionManager),
                readOnly);
    }
}
