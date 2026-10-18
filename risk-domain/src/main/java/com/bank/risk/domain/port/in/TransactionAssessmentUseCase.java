package com.bank.risk.domain.port.in;

import com.bank.risk.domain.RiskAssessment;

/** {@code POST /api/v2/risk/assess}: decide once per transaction from facts risk resolves itself. */
public interface TransactionAssessmentUseCase {

    RiskAssessment assess(TransactionAssessmentCommand command);
}
