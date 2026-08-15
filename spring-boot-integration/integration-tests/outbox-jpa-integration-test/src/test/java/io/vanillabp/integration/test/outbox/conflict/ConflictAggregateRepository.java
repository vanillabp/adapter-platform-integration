package io.vanillabp.integration.test.outbox.conflict;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConflictAggregateRepository extends JpaRepository<ConflictAggregate, Long> {

}
