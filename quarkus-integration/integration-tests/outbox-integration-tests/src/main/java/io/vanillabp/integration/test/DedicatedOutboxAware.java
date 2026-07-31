package io.vanillabp.integration.test;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Attributes the {@link Aggregate} to the application-defined
 * {@link DedicatedOutbox} - the recipe for isolating a high-load process onto its
 * own outbox (and, with two outbox beans in the container, the attribution required
 * to resolve the ambiguity).
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
