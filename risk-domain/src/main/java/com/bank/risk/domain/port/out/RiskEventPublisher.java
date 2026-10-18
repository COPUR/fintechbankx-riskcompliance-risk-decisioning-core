package com.bank.risk.domain.port.out;

import com.bank.risk.domain.RiskDomainEvent;

import java.util.List;

/**
 * Publishes risk domain events. Implementations must write them atomically
 * with the aggregate they came from (transactional outbox), so an event is
 * never published for a decision that was not stored, and never lost for one
 * that was.
 */
public interface RiskEventPublisher {

    void publish(List<RiskDomainEvent> events);
}
