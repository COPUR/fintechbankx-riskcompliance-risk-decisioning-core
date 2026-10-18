package com.bank.risk.infrastructure.config;

import com.bank.risk.application.LoanEligibilityService;
import com.bank.risk.application.RiskAssessmentService;
import com.bank.risk.application.TransactionAssessmentService;
import com.bank.risk.domain.port.in.LoanEligibilityUseCase;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import com.bank.risk.domain.port.in.TransactionAssessmentUseCase;
import com.bank.risk.domain.port.out.AssessmentHistory;
import com.bank.risk.domain.port.out.CustomerCreditProfilePort;
import com.bank.risk.domain.port.out.HighRiskCountryList;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.domain.service.CounterpartyCountryResolver;
import com.bank.risk.domain.service.HighRiskCountryPolicy;
import com.bank.risk.domain.service.LoanEligibilityPolicy;
import com.bank.risk.domain.service.RiskPolicyService;
import com.bank.risk.infrastructure.transaction.TransactionalRiskAssessmentUseCase;
import com.bank.risk.infrastructure.transaction.TransactionalTransactionAssessmentUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class RiskConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RiskConfiguration.class);

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

    /**
     * The only {@link TransactionAssessmentUseCase} bean (v2): the application
     * service, with risk's own fact sources, wrapped in its transaction boundary.
     * RISK_HOME_COUNTRY (risk.country.home) resolves internal ACC- accounts;
     * unset, they count as unknown, so high risk, and the log says so once.
     */
    @Bean
    TransactionAssessmentUseCase transactionAssessmentUseCase(
            RiskPolicyService riskPolicyService,
            HighRiskCountryList highRiskCountryList,
            AssessmentHistory assessmentHistory,
            RiskAssessmentRepository riskAssessmentRepository,
            RiskEventPublisher riskEventPublisher,
            PlatformTransactionManager transactionManager,
            @Value("${risk.country.home:}") String homeCountry,
            @Value("${risk.country.list-max-age:P35D}") Duration listMaxAge,
            @Value("${risk.velocity.window:PT24H}") Duration velocityWindow
    ) {
        CounterpartyCountryResolver countries = new CounterpartyCountryResolver(homeCountry);
        if (!countries.hasHomeCountry()) {
            log.warn("RISK_HOME_COUNTRY (risk.country.home) is not a country code: internal ACC- counterparties are decided as high risk");
        }
        TransactionAssessmentService service = new TransactionAssessmentService(riskPolicyService, countries,
                new HighRiskCountryPolicy(listMaxAge, Clock.systemUTC()), velocityWindow, highRiskCountryList,
                assessmentHistory, riskAssessmentRepository, riskEventPublisher, Clock.systemUTC());
        return new TransactionalTransactionAssessmentUseCase(service, new TransactionTemplate(transactionManager));
    }

    /** Loan eligibility reads the customer service and stores nothing: no transaction boundary. */
    @Bean
    LoanEligibilityUseCase loanEligibilityUseCase(CustomerCreditProfilePort customerCreditProfilePort) {
        return new LoanEligibilityService(customerCreditProfilePort, new LoanEligibilityPolicy());
    }
}
