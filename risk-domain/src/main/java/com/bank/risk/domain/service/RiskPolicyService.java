package com.bank.risk.domain.service;

import com.bank.risk.domain.CounterpartyCountry;
import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.port.in.TransactionAssessmentCommand;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transaction risk policy. Amount thresholds are per currency: an amount is
 * only compared with thresholds stated in its own currency. An amount in a
 * currency without thresholds is never decided as low risk: the amount rules
 * are skipped, UNSUPPORTED_CURRENCY is added and the decision is at least
 * REVIEW. The country and velocity rules apply to every currency.
 *
 * Suspicious patterns (since rsk-policy-v3) restore the monolith's fraud
 * refusals (payment-context FraudDetectionServiceAdapter.isSuspiciousPattern):
 * a round amount or a large mobile payment is decided BLOCK whatever the
 * score, and only in a currency that has pattern limits (USD today).
 *
 * The same rules score facts the caller stated (v1, {@link #evaluate(RiskEvaluationCommand)})
 * and facts risk resolved itself (v2, {@link #evaluate(TransactionAssessmentCommand, CounterpartyCountry, boolean, int)}).
 */
public class RiskPolicyService {

    public static final String UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY";
    public static final String SUSPICIOUS_ROUND_AMOUNT = "SUSPICIOUS_ROUND_AMOUNT";
    public static final String SUSPICIOUS_MOBILE_AMOUNT = "SUSPICIOUS_MOBILE_AMOUNT";

    /** velocityScore from which MEDIUM_VELOCITY (+15) applies; {@link VelocityPolicy} maps activity onto this scale. */
    public static final int MEDIUM_VELOCITY_FROM = 40;
    /** velocityScore from which HIGH_VELOCITY (+30) applies. */
    public static final int HIGH_VELOCITY_FROM = 70;

    /**
     * Version of these rules, stored with every decision. rsk-policy-v1 compared
     * every amount with 10000/50000 whatever its currency; v2 has per-currency
     * thresholds; v3 keeps v2's rules and adds the monolith's suspicious
     * patterns; v4 keeps v3's thresholds and patterns and applies them to facts
     * risk resolves itself on v2 of the API (high-risk country from the
     * risk-owned list, velocity from the customer's decisions of record,
     * VelocityPolicy). Change it whenever a rule, threshold or fact source changes.
     */
    public static final String RULE_SET_VERSION = "rsk-policy-v4";

    /** The thresholds the policy has always used, now stated as USD (monolith evidence: home currency USD). */
    public static final Map<String, AmountThresholds> USD_THRESHOLDS = Map.of(
            "USD", new AmountThresholds(new BigDecimal("10000"), new BigDecimal("50000")));

    /**
     * The monolith's pattern figures, in USD like the thresholds: a multiple of 1000 above 10000, and a
     * MOBILE_PAYMENT above 5000.
     */
    public static final Map<String, SuspiciousPatternLimits> USD_PATTERNS = Map.of(
            "USD", new SuspiciousPatternLimits(new BigDecimal("1000"), new BigDecimal("10000"), new BigDecimal("5000")));

    private final Map<String, AmountThresholds> thresholds;
    private final Map<String, SuspiciousPatternLimits> patterns;

    public RiskPolicyService() {
        this(USD_THRESHOLDS, USD_PATTERNS);
    }

    public RiskPolicyService(Map<String, AmountThresholds> thresholdsByCurrency) {
        this(thresholdsByCurrency, USD_PATTERNS);
    }

    public RiskPolicyService(Map<String, AmountThresholds> thresholdsByCurrency,
                             Map<String, SuspiciousPatternLimits> patternsByCurrency) {
        if (thresholdsByCurrency == null || thresholdsByCurrency.isEmpty()) {
            throw new IllegalArgumentException("at least one currency needs amount thresholds");
        }
        this.thresholds = Map.copyOf(thresholdsByCurrency);
        this.patterns = Map.copyOf(patternsByCurrency == null ? Map.of() : patternsByCurrency);
    }

    /** v1: the caller stated highRiskCountry and velocityScore (CALLER_ATTESTED). */
    public RiskAssessment evaluate(RiskEvaluationCommand command) {
        Outcome outcome = score(command.amount(), command.currency(), command.highRiskCountry(), command.velocityScore(),
                command.paymentType());
        return RiskAssessment.create(command, outcome.score, outcome.decision, outcome.reasons, RULE_SET_VERSION);
    }

    /** v2: risk resolved the counterparty country, its risk and the velocity itself (RISK_RESOLVED). */
    public RiskAssessment evaluate(TransactionAssessmentCommand request, CounterpartyCountry country, boolean highRiskCountry,
                                   int velocityScore) {
        Outcome outcome = score(request.amount(), request.currency(), highRiskCountry, velocityScore, request.paymentType());
        return RiskAssessment.resolve(request, country, highRiskCountry, velocityScore, outcome.score, outcome.decision,
                outcome.reasons, RULE_SET_VERSION);
    }

    private record Outcome(int score, RiskDecision decision, List<String> reasons) {
    }

    private Outcome score(BigDecimal amount, String currency, boolean highRiskCountry, int velocityScore, PaymentType paymentType) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        AmountThresholds limits = thresholds.get(currency);
        if (limits == null) {
            reasons.add(UNSUPPORTED_CURRENCY);
        } else {
            if (amount.compareTo(limits.high()) > 0) {
                score += 30;
                reasons.add("HIGH_AMOUNT");
            }
            if (amount.compareTo(limits.veryHigh()) > 0) {
                score += 20;
                reasons.add("VERY_HIGH_AMOUNT");
            }
        }

        if (highRiskCountry) {
            score += 40;
            reasons.add("HIGH_RISK_COUNTRY");
        }

        if (velocityScore >= HIGH_VELOCITY_FROM) {
            score += 30;
            reasons.add("HIGH_VELOCITY");
        } else if (velocityScore >= MEDIUM_VELOCITY_FROM) {
            score += 15;
            reasons.add("MEDIUM_VELOCITY");
        }

        if (score > 100) {
            score = 100;
        }

        // Patterns only where the amount rules apply, so an unsupported currency stays REVIEW.
        SuspiciousPatternLimits pattern = limits == null ? null : patterns.get(currency);
        boolean suspicious = false;
        if (pattern != null && pattern.isSuspiciousRoundAmount(amount)) {
            reasons.add(SUSPICIOUS_ROUND_AMOUNT);
            suspicious = true;
        }
        if (pattern != null && pattern.isSuspiciousMobileAmount(paymentType, amount)) {
            reasons.add(SUSPICIOUS_MOBILE_AMOUNT);
            suspicious = true;
        }

        RiskDecision decision;
        if (suspicious || score >= 80) {
            decision = RiskDecision.BLOCK;
        } else if (score >= 50 || limits == null) {
            decision = RiskDecision.REVIEW;
        } else {
            decision = RiskDecision.ALLOW;
        }
        return new Outcome(score, decision, reasons);
    }
}
