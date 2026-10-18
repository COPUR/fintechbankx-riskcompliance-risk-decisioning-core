package com.bank.risk.domain;

import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.port.in.TransactionAssessmentCommand;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RiskAssessment {
    private final RiskAssessmentId id;
    private final String transactionId;
    private final BigDecimal amount;
    private final String currency;
    private final boolean highRiskCountry;
    private final int velocityScore;
    private final int score;
    private final RiskDecision decision;
    private final List<String> reasons;
    private final Instant assessedAt;
    private final AttestationSource attestationSource;
    private final String attestedBy;
    private final String ruleSetVersion;
    private final PaymentType paymentType;
    private final String customerId;
    private final String counterpartyCountry;
    private final List<RiskDomainEvent> domainEvents = new ArrayList<>();

    private RiskAssessment(
            RiskAssessmentId id,
            String transactionId,
            BigDecimal amount,
            String currency,
            boolean highRiskCountry,
            int velocityScore,
            int score,
            RiskDecision decision,
            List<String> reasons,
            Instant assessedAt,
            AttestationSource attestationSource,
            String attestedBy,
            String ruleSetVersion,
            PaymentType paymentType,
            String customerId,
            String counterpartyCountry
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        if (velocityScore < 0 || velocityScore > 100) {
            throw new IllegalArgumentException("velocityScore must be between 0 and 100");
        }
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        this.transactionId = transactionId;
        this.amount = amount;
        this.currency = currency;
        this.highRiskCountry = highRiskCountry;
        this.velocityScore = velocityScore;
        this.score = score;
        this.decision = Objects.requireNonNull(decision, "decision is required");
        this.reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons are required"));
        this.assessedAt = Objects.requireNonNull(assessedAt, "assessedAt is required");
        this.attestationSource = Objects.requireNonNull(attestationSource, "attestationSource is required");
        if (attestedBy == null || attestedBy.isBlank()) {
            throw new IllegalArgumentException("attestedBy (the caller stating the risk facts) is required");
        }
        this.attestedBy = attestedBy;
        if (ruleSetVersion == null || ruleSetVersion.isBlank()) {
            throw new IllegalArgumentException("ruleSetVersion is required");
        }
        this.ruleSetVersion = ruleSetVersion;
        this.paymentType = Objects.requireNonNull(paymentType, "paymentType is required");
        if (attestationSource == AttestationSource.RISK_RESOLVED && (customerId == null || customerId.isBlank())) {
            throw new IllegalArgumentException("customerId is required on a RISK_RESOLVED decision (velocity is per customer)");
        }
        this.customerId = customerId == null || customerId.isBlank() ? null : customerId;
        this.counterpartyCountry = counterpartyCountry == null ? null : CounterpartyCountry.of(counterpartyCountry).code();
    }

    /**
     * Records the decision the policy made for these inputs. The inputs are
     * kept with the decision so a retry can be checked against all of them.
     */
    public static RiskAssessment create(
            RiskEvaluationCommand inputs,
            int score,
            RiskDecision decision,
            List<String> reasons,
            String ruleSetVersion
    ) {
        Objects.requireNonNull(inputs, "inputs are required");
        RiskAssessment assessment = new RiskAssessment(
                RiskAssessmentId.generate(),
                inputs.transactionId(),
                inputs.amount(),
                inputs.currency(),
                inputs.highRiskCountry(),
                inputs.velocityScore(),
                score,
                decision,
                reasons,
                // Microseconds: the precision the decision of record is stored with, so a
                // retry returns exactly the timestamp the first response carried.
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                // The policy only applies thresholds to the caller's statements.
                AttestationSource.CALLER_ATTESTED,
                inputs.attestedBy(),
                ruleSetVersion,
                inputs.paymentType(),
                null,
                null
        );
        assessment.domainEvents.add(assessment.assessedEvent());
        return assessment;
    }

    /**
     * Records a decision whose facts risk resolved itself (v2): the counterparty
     * country from the account, its risk from the risk-owned list, the velocity
     * from the customer's decisions of record. attested_by names the caller
     * that asked; the customer and the country are stored with the decision.
     */
    public static RiskAssessment resolve(
            TransactionAssessmentCommand request,
            CounterpartyCountry counterpartyCountry,
            boolean highRiskCountry,
            int velocityScore,
            int score,
            RiskDecision decision,
            List<String> reasons,
            String ruleSetVersion
    ) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(counterpartyCountry, "counterpartyCountry is required (UNKNOWN when unresolved)");
        RiskAssessment assessment = new RiskAssessment(
                RiskAssessmentId.generate(),
                request.transactionId(),
                request.amount(),
                request.currency(),
                highRiskCountry,
                velocityScore,
                score,
                decision,
                reasons,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                AttestationSource.RISK_RESOLVED,
                request.requestedBy(),
                ruleSetVersion,
                request.paymentType(),
                request.customerId(),
                counterpartyCountry.code()
        );
        assessment.domainEvents.add(assessment.assessedEvent());
        return assessment;
    }

    /**
     * Rebuilds a stored assessment. The decision and score are kept as they
     * were made, even if the policy has changed since. Raises no event: the
     * assessment was announced when it was first made.
     */
    public static RiskAssessment rehydrate(RiskAssessmentSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        return new RiskAssessment(
                snapshot.id(),
                snapshot.transactionId(),
                snapshot.amount(),
                snapshot.currency(),
                snapshot.highRiskCountry(),
                snapshot.velocityScore(),
                snapshot.score(),
                snapshot.decision(),
                snapshot.reasons(),
                snapshot.assessedAt(),
                snapshot.attestationSource(),
                snapshot.attestedBy(),
                snapshot.ruleSetVersion(),
                snapshot.paymentType(),
                snapshot.customerId(),
                snapshot.counterpartyCountry()
        );
    }

    /**
     * True when a repeated request repeats every input of this decision
     * (amount compared by value, so 100.0 equals 100.00), so the stored
     * decision can be returned for it. Any other difference is a different
     * question under a reused transaction id.
     */
    public boolean matches(RiskEvaluationCommand request) {
        return transactionId.equals(request.transactionId())
                && currency.equals(request.currency())
                && amount.compareTo(request.amount()) == 0
                && highRiskCountry == request.highRiskCountry()
                && velocityScore == request.velocityScore()
                && paymentType == request.paymentType();
    }

    /**
     * Answers a repeated request for this transaction: the stored decision when
     * every input is repeated (no new event is raised), otherwise
     * {@link TransactionAlreadyAssessedException}. A stored decision is never replaced.
     */
    public RiskAssessment answerRetry(RiskEvaluationCommand request) {
        if (!matches(request)) {
            throw new TransactionAlreadyAssessedException(request.transactionId());
        }
        return this;
    }

    /**
     * True when a repeated v2 request asks the same question: same transaction,
     * customer, amount (by value), currency and payment type, and a counterparty
     * account that resolves to the same country (or to none, as before). Risk's
     * own facts (highRiskCountry, velocityScore) are not compared: velocity
     * moves with time and the first decision stands. A caller-attested (v1)
     * decision never answers a v2 retry: it has no customer.
     */
    public boolean matches(TransactionAssessmentCommand request, CounterpartyCountry country) {
        return attestationSource == AttestationSource.RISK_RESOLVED
                && transactionId.equals(request.transactionId())
                && Objects.equals(customerId, request.customerId())
                && currency.equals(request.currency())
                && amount.compareTo(request.amount()) == 0
                && paymentType == request.paymentType()
                && Objects.equals(counterpartyCountry, country.code());
    }

    /** Answers a repeated v2 request: the stored decision, or {@link TransactionAlreadyAssessedException}. */
    public RiskAssessment answerRetry(TransactionAssessmentCommand request, CounterpartyCountry country) {
        if (!matches(request, country)) {
            throw new TransactionAlreadyAssessedException(request.transactionId());
        }
        return this;
    }

    private RiskAssessedEvent assessedEvent() {
        return new RiskAssessedEvent(UUID.randomUUID(), assessedAt, id, transactionId, decision, score,
                amount, currency, reasons, assessedAt, attestationSource);
    }

    /** Events raised since the assessment was created, oldest first; publish them after saving. */
    public List<RiskDomainEvent> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    public RiskAssessmentId getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isHighRiskCountry() {
        return highRiskCountry;
    }

    public int getVelocityScore() {
        return velocityScore;
    }

    public int getScore() {
        return score;
    }

    public RiskDecision getDecision() {
        return decision;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }

    public AttestationSource getAttestationSource() {
        return attestationSource;
    }

    /** The calling client (azp) or staff subject that stated the risk facts. */
    public String getAttestedBy() {
        return attestedBy;
    }

    /** The policy rule set that made this decision; recorded, not compared on retry. */
    public String getRuleSetVersion() {
        return ruleSetVersion;
    }

    public boolean isBlocked() {
        return decision == RiskDecision.BLOCK;
    }

    public boolean requiresManualReview() {
        return decision == RiskDecision.REVIEW;
    }

    /** The payment type the caller stated; a fact of the decision, compared on retry. */
    public PaymentType getPaymentType() {
        return paymentType;
    }

    /** The customer the transaction belongs to (v2); null on caller-attested decisions. */
    public String getCustomerId() {
        return customerId;
    }

    /** ISO alpha-2 country risk resolved from the counterparty account (v2); null when unresolved or caller-attested. */
    public String getCounterpartyCountry() {
        return counterpartyCountry;
    }

}
