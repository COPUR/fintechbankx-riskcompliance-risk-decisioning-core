package com.bank.risk.domain.service;

/**
 * Maps the customer's recent activity onto the velocityScore scale
 * {@link RiskPolicyService} has always scored (0..100; MEDIUM_VELOCITY from
 * {@value RiskPolicyService#MEDIUM_VELOCITY_FROM}, HIGH_VELOCITY from
 * {@value RiskPolicyService#HIGH_VELOCITY_FROM}).
 *
 * <p>Activity is the number of decisions of record risk already holds for the
 * customer in the trailing window (RISK_VELOCITY_WINDOW, default PT24H), read
 * through {@link com.bank.risk.domain.port.out.AssessmentHistory}. Each prior
 * assessment adds {@value #POINTS_PER_ASSESSMENT} points, capped at 100:
 *
 * <pre>
 * prior assessments   0   1   2   3   4    5   6   7    8   9  10+
 * velocityScore       0  10  20  30  40   50  60  70   80  90  100
 * policy reason       -   -   -   -  MEDIUM_VELOCITY   HIGH_VELOCITY
 * </pre>
 *
 * So a fifth payment within the window scores MEDIUM_VELOCITY (+15) and an
 * eighth HIGH_VELOCITY (+30), the thresholds callers used to attest under v1.
 * Only decisions with a customer id count (v1 rows have none), and the
 * decision being made is not yet stored, so it does not count itself. The
 * figures are the first owner-computed mapping and are a policy choice to be
 * confirmed with Group Risk (rule-set version rsk-policy-v4).
 */
public final class VelocityPolicy {

    public static final int POINTS_PER_ASSESSMENT = 10;
    public static final int MAX_SCORE = 100;

    private VelocityPolicy() {
    }

    public static int velocityScore(int priorAssessmentsInWindow) {
        if (priorAssessmentsInWindow < 0) {
            throw new IllegalArgumentException("priorAssessmentsInWindow must not be negative");
        }
        return (int) Math.min((long) MAX_SCORE, (long) priorAssessmentsInWindow * POINTS_PER_ASSESSMENT);
    }
}
