package io.vanillabp.integration.test.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AggregateRepository extends JpaRepository<Aggregate, Long> {

}
