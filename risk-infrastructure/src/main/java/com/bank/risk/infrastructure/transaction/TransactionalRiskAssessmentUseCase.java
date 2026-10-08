package com.bank.risk.infrastructure.transaction;

import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Optional;

/**
 * Transaction boundary for the framework-free application service: the
 * assessment row and its outbox row are written in one database transaction.
 * If the insert loses a race on the unique transaction_id, the whole
 * transaction rolls back and no event is left behind.
 */
public class TransactionalRiskAssessmentUseCase implements RiskAssessmentUseCase {

    private final RiskAssessmentUseCase delegate;
    private final TransactionOperations writeTransaction;
    private final TransactionOperations readTransaction;

    public TransactionalRiskAssessmentUseCase(RiskAssessmentUseCase delegate,
                                              TransactionOperations writeTransaction,
                                              TransactionOperations readTransaction) {
        this.delegate = delegate;
        this.writeTransaction = writeTransaction;
        this.readTransaction = readTransaction;
    }

    @Override
    public RiskAssessment assess(RiskEvaluationCommand command) {
        return writeTransaction.execute(status -> delegate.assess(command));
    }

    @Override
    public Optional<RiskAssessment> findByTransactionId(String transactionId) {
        return readTransaction.execute(status -> delegate.findByTransactionId(transactionId));
    }
}
