package com.bank.risk.application;

import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.command.RiskEvaluationCommand;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.domain.service.RiskPolicyService;

import java.util.Optional;

/**
 * Assesses a transaction once and announces the decision. Saving the
 * assessment and publishing its event must share one transaction; this class
 * has no framework, so the adapter that drives it opens that transaction.
 */
public class RiskAssessmentService implements RiskAssessmentUseCase {
    private final RiskPolicyService policyService;
    private final RiskAssessmentRepository repository;
    private final RiskEventPublisher eventPublisher;

    public RiskAssessmentService(RiskPolicyService policyService, RiskAssessmentRepository repository,
                                 RiskEventPublisher eventPublisher) {
        this.policyService = policyService;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public RiskAssessment assess(RiskEvaluationCommand command) {
        Optional<RiskAssessment> existing = repository.findByTransactionId(command.transactionId());
        if (existing.isPresent()) {
            // Retries get the original decision and publish nothing; a different transaction
            // under a reused id is refused.
            if (!existing.get().matches(command.currency(), command.amount())) {
                throw new TransactionAlreadyAssessedException(command.transactionId());
            }
            return existing.get();
        }

        RiskAssessment assessment = policyService.evaluate(command);
        RiskAssessment saved = repository.save(assessment);
        eventPublisher.publish(assessment.getDomainEvents());
        assessment.clearDomainEvents();
        return saved;
    }

    @Override
    public Optional<RiskAssessment> findByTransactionId(String transactionId) {
        return repository.findByTransactionId(transactionId);
    }
}
