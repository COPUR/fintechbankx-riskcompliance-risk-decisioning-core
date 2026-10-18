package com.bank.risk.infrastructure.transaction;

import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.port.in.TransactionAssessmentCommand;
import com.bank.risk.domain.port.in.TransactionAssessmentUseCase;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Transaction boundary for the v2 application service: the list read, the
 * velocity count, the assessment row and its outbox row share one database
 * transaction. If the insert loses a race on the unique transaction_id, the
 * whole transaction rolls back and no event is left behind.
 */
public class TransactionalTransactionAssessmentUseCase implements TransactionAssessmentUseCase {

    private final TransactionAssessmentUseCase delegate;
    private final TransactionOperations writeTransaction;

    public TransactionalTransactionAssessmentUseCase(TransactionAssessmentUseCase delegate, TransactionOperations writeTransaction) {
        this.delegate = delegate;
        this.writeTransaction = writeTransaction;
    }

    @Override
    public RiskAssessment assess(TransactionAssessmentCommand command) {
        return writeTransaction.execute(status -> delegate.assess(command));
    }
}
