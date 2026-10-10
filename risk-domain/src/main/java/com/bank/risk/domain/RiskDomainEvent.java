package com.bank.risk.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A fact raised by a risk aggregate. Published through
 * {@link com.bank.risk.domain.port.out.RiskEventPublisher} in the same
 * transaction that stores the aggregate.
 */
public sealed interface RiskDomainEvent permits RiskAssessedEvent {

    /** Unique id; consumers de-duplicate on it. */
    UUID eventId();

    Instant occurredAt();
}
