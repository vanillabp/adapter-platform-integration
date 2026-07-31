package io.vanillabp.integration.test.outbox.mixed;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HotAggregateRepository extends JpaRepository<HotAggregate, Long> {
}
