package com.bank.risk.domain.port.out;

import java.time.Instant;

/**
 * Risk's own decisions of record, read for velocity: how many assessments a
 * customer already has that were decided at or after {@code since}. The
 * adapter serialises the count per customer for the rest of the calling
 * transaction, so concurrent assessments of one customer count each other.
 */
public interface AssessmentHistory {

    int countAssessments(String customerId, Instant since);
}
