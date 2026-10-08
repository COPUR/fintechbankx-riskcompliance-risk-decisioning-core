package com.bank.risk.infrastructure.transaction;

import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionalRiskAssessmentUseCaseTest {

    private final RecordingTransactionManager transactions = new RecordingTransactionManager();
    private final RiskAssessmentUseCase delegate = mock(RiskAssessmentUseCase.class);
    private final TransactionalRiskAssessmentUseCase useCase = new TransactionalRiskAssessmentUseCase(
        delegate, new TransactionTemplate(transactions), readOnly(transactions));

    private static final RiskEvaluationCommand COMMAND =
        new RiskEvaluationCommand("PAY-TX-1", new BigDecimal("100.00"), "AED", false, 0, "svc-pay-initiation-settlement");

    @Test
    void anAssessmentCommitsInOneReadWriteTransaction() {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("PAY-TX-1", new BigDecimal("100.00"), "AED", false, 0, "svc-pay-initiation-settlement"), 0,
            RiskDecision.ALLOW, List.of(), "rsk-policy-v2");
        when(delegate.assess(COMMAND)).thenAnswer(invocation -> {
            transactions.log.add("assess");
            return assessment;
        });

        assertThat(useCase.assess(COMMAND)).isSameAs(assessment);
        assertThat(transactions.log).containsExactly("begin readOnly=false", "assess", "commit");
    }

    @Test
    void aFailedAssessmentRollsBackSoNoOutboxRowSurvives() {
        when(delegate.assess(COMMAND)).thenThrow(new IllegalStateException("duplicate transaction_id"));

        assertThatThrownBy(() -> useCase.assess(COMMAND)).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.log).containsExactly("begin readOnly=false", "rollback");
    }

    @Test
    void lookupsRunInAReadOnlyTransaction() {
        when(delegate.findByTransactionId("PAY-TX-2")).thenReturn(Optional.empty());

        assertThat(useCase.findByTransactionId("PAY-TX-2")).isEmpty();
        assertThat(transactions.log).containsExactly("begin readOnly=true", "commit");
    }

    private static TransactionTemplate readOnly(RecordingTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        return template;
    }

    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final List<String> log = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            log.add("begin readOnly=" + definition.isReadOnly());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            log.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            log.add("rollback");
        }

    }
}
