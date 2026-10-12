package io.vanillabp.integration.test.secondary;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Assigns the aggregate of the test to the outbox which never dispatches. So the JDBC
 * outbox, which comes with the data source, stays out of this test. The delivery log still
 * uses that data source. The records which this test reads really live there.
 */
@ApplicationScoped
public class OrderOutboxAware implements PhaseTwoOutboxAware<OrderAggregate> {

  @Inject
  OrderOutbox orderOutbox;

  @Override
  public Class<OrderAggregate> getAggregateClass() {

    return OrderAggregate.class;

  }

  @Override
  public PhaseTwoOutbox getPhaseTwoOutbox() {

    return orderOutbox;

  }

}
