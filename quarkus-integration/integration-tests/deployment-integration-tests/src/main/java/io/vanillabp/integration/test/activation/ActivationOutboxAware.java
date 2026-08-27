package io.vanillabp.integration.test.activation;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Attributes the test's aggregate to the outbox which never dispatches, so the JDBC
 * outbox the data source brings along stays out of this test.
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
