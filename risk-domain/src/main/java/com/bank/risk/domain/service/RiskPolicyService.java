package com.bank.risk.domain.service;

import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;

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
 * Suspicious patterns (rsk-policy-v3) restore the monolith's fraud refusals
 * (payment-context FraudDetectionServiceAdapter.isSuspiciousPattern): a round
 * amount or a large mobile payment is decided BLOCK whatever the score, and only
 * in a currency that has pattern limits (USD today).
 */
public class RiskPolicyService {

    public static final String UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY";
    public static final String SUSPICIOUS_ROUND_AMOUNT = "SUSPICIOUS_ROUND_AMOUNT";
    public static final String SUSPICIOUS_MOBILE_AMOUNT = "SUSPICIOUS_MOBILE_AMOUNT";

    /**
     * Version of these rules, stored with every decision. rsk-policy-v1 compared
     * every amount with 10000/50000 whatever its currency; v2 has per-currency
     * thresholds; v3 keeps v2's rules and adds the monolith's suspicious
     * patterns. Change it whenever a rule or threshold changes.
     */
    public static final String RULE_SET_VERSION = "rsk-policy-v3";

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

    public RiskAssessment evaluate(RiskEvaluationCommand command) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        AmountThresholds limits = thresholds.get(command.currency());
        if (limits == null) {
            reasons.add(UNSUPPORTED_CURRENCY);
        } else {
            if (command.amount().compareTo(limits.high()) > 0) {
                score += 30;
                reasons.add("HIGH_AMOUNT");
            }
            if (command.amount().compareTo(limits.veryHigh()) > 0) {
                score += 20;
                reasons.add("VERY_HIGH_AMOUNT");
            }
        }

        if (command.highRiskCountry()) {
            score += 40;
            reasons.add("HIGH_RISK_COUNTRY");
        }

        if (command.velocityScore() >= 70) {
            score += 30;
            reasons.add("HIGH_VELOCITY");
        } else if (command.velocityScore() >= 40) {
            score += 15;
            reasons.add("MEDIUM_VELOCITY");
        }

        if (score > 100) {
            score = 100;
        }

        // Patterns only where the amount rules apply, so an unsupported currency stays REVIEW.
        SuspiciousPatternLimits pattern = limits == null ? null : patterns.get(command.currency());
        boolean suspicious = false;
        if (pattern != null && pattern.isSuspiciousRoundAmount(command.amount())) {
            reasons.add(SUSPICIOUS_ROUND_AMOUNT);
            suspicious = true;
        }
        if (pattern != null && pattern.isSuspiciousMobileAmount(command.paymentType(), command.amount())) {
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

        return RiskAssessment.create(command, score, decision, reasons, RULE_SET_VERSION);
    }
}
