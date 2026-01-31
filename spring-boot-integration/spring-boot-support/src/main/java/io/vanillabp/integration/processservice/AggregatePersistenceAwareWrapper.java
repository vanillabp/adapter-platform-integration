package io.vanillabp.integration.processservice;

import io.vanillabp.integration.spi.aggregate.AggregatePersistenceAware;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

@RequiredArgsConstructor
public class AggregatePersistenceAwareWrapper<A> implements io.vanillabp.intergration.adapter.migration.spi.AggregatePersistenceAware<A> {

  @Delegate
  private final AggregatePersistenceAware<A> delegate;

}
