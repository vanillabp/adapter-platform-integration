package io.vanillabp.integration.test.activation;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Assigns the aggregate of the test to the outbox which never dispatches. So the JDBC
 * outbox, which comes with the data source, stays out of this test.
 */
@ApplicationScoped
public class ActivationOutboxAware implements PhaseTwoOutboxAware<ActivationAggregate> {

  @Inject
  ActivationOutbox activationOutbox;

  @Override
  public Class<ActivationAggregate> getAggregateClass() {

    return ActivationAggregate.class;

  }

  @Override
  public PhaseTwoOutbox getPhaseTwoOutbox() {

    return activationOutbox;

  }

}
