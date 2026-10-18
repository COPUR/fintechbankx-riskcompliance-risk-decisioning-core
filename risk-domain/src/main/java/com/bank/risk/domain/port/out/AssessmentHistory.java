package com.bank.risk.domain.port.out;

import java.time.Instant;

/**
 * Risk's own decisions of record, read for velocity: how many assessments a
 * customer already has that were decided at or after {@code since}.
 */
public interface AssessmentHistory {

    int countAssessments(String customerId, Instant since);
}
