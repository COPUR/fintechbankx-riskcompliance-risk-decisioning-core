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
 */
public class RiskPolicyService {

    public static final String UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY";

    /** The thresholds the policy has always used, now stated as USD (monolith evidence: home currency USD). */
    public static final Map<String, AmountThresholds> USD_THRESHOLDS = Map.of(
            "USD", new AmountThresholds(new BigDecimal("10000"), new BigDecimal("50000")));

    private final Map<String, AmountThresholds> thresholds;

    public RiskPolicyService() {
        this(USD_THRESHOLDS);
    }

    public RiskPolicyService(Map<String, AmountThresholds> thresholdsByCurrency) {
        if (thresholdsByCurrency == null || thresholdsByCurrency.isEmpty()) {
            throw new IllegalArgumentException("at least one currency needs amount thresholds");
        }
        this.thresholds = Map.copyOf(thresholdsByCurrency);
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

        RiskDecision decision;
        if (score >= 80) {
            decision = RiskDecision.BLOCK;
        } else if (score >= 50 || limits == null) {
            decision = RiskDecision.REVIEW;
        } else {
            decision = RiskDecision.ALLOW;
        }

        return RiskAssessment.create(command, score, decision, reasons);
    }
}
