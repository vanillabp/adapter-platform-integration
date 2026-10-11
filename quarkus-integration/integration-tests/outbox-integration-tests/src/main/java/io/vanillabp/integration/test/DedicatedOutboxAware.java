package io.vanillabp.integration.test;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Assigns the {@link Aggregate} to the {@link DedicatedOutbox} which the application
 * defines. This is the recipe for moving a process with high load onto its own outbox.
 * With two outbox beans in the container, this assignment is also what resolves the
 * ambiguity.
 */
@ApplicationScoped
public class DedicatedOutboxAware implements PhaseTwoOutboxAware<Aggregate> {

  @Inject
  DedicatedOutbox dedicatedOutbox;

  @Override
  public Class<Aggregate> getAggregateClass() {

    return Aggregate.class;

  }

  @Override
  public PhaseTwoOutbox getPhaseTwoOutbox() {

    return dedicatedOutbox;

  }

}
