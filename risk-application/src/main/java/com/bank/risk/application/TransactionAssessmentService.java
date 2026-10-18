package com.bank.risk.application;

import com.bank.risk.domain.CounterpartyCountry;
import com.bank.risk.domain.HighRiskCountries;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.port.in.TransactionAssessmentCommand;
import com.bank.risk.domain.port.in.TransactionAssessmentUseCase;
import com.bank.risk.domain.port.out.AssessmentHistory;
import com.bank.risk.domain.port.out.HighRiskCountryList;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.domain.service.CounterpartyCountryResolver;
import com.bank.risk.domain.service.HighRiskCountryPolicy;
import com.bank.risk.domain.service.RiskPolicyService;
import com.bank.risk.domain.service.VelocityPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * POST /api/v2/risk/assess: the caller sends ids and amounts; risk resolves
 * the facts from its own sources, decides once per transaction and announces
 * the decision. Order: a retry is answered from the stored decision before any
 * source is read; then the country (from the account), its risk (from the
 * list, which must be fresh), the velocity (from the customer's decisions of
 * record in the trailing window), the policy, save, publish. Saving and
 * publishing share one transaction opened by the adapter that drives this
 * class; this class has no framework.
 */
public class TransactionAssessmentService implements TransactionAssessmentUseCase {

    private final RiskPolicyService policy;
    private final CounterpartyCountryResolver countries;
    private final HighRiskCountryPolicy countryRisk;
    private final Duration velocityWindow;
    private final HighRiskCountryList highRiskCountries;
    private final AssessmentHistory history;
    private final RiskAssessmentRepository repository;
    private final RiskEventPublisher eventPublisher;
    private final Clock clock;

    public TransactionAssessmentService(RiskPolicyService policy,
                                        CounterpartyCountryResolver countries,
                                        HighRiskCountryPolicy countryRisk,
                                        Duration velocityWindow,
                                        HighRiskCountryList highRiskCountries,
                                        AssessmentHistory history,
                                        RiskAssessmentRepository repository,
                                        RiskEventPublisher eventPublisher,
                                        Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy is required");
        this.countries = Objects.requireNonNull(countries, "countries is required");
        this.countryRisk = Objects.requireNonNull(countryRisk, "countryRisk is required");
        Objects.requireNonNull(velocityWindow, "velocityWindow is required");
        if (velocityWindow.isZero() || velocityWindow.isNegative()) {
            throw new IllegalArgumentException("velocityWindow must be positive");
        }
        this.velocityWindow = velocityWindow;
        this.highRiskCountries = Objects.requireNonNull(highRiskCountries, "highRiskCountries is required");
        this.history = Objects.requireNonNull(history, "history is required");
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public RiskAssessment assess(TransactionAssessmentCommand request) {
        CounterpartyCountry country = countries.resolve(request.counterpartyAccount());

        Optional<RiskAssessment> existing = repository.findByTransactionId(request.transactionId());
        if (existing.isPresent()) {
            // Retries get the original decision and publish nothing; the aggregate refuses any
            // other question under a reused id. No source is read for a retry.
            return existing.get().answerRetry(request, country);
        }

        Optional<HighRiskCountries> list = highRiskCountries.current();
        boolean highRiskCountry = countryRisk.isHighRisk(country, list);
        Instant since = clock.instant().minus(velocityWindow);
        int velocityScore = VelocityPolicy.velocityScore(history.countAssessments(request.customerId(), since));

        RiskAssessment assessment = policy.evaluate(request, country, highRiskCountry, velocityScore);
        RiskAssessment saved = repository.save(assessment);
        eventPublisher.publish(assessment.getDomainEvents());
        assessment.clearDomainEvents();
        return saved;
    }
}
