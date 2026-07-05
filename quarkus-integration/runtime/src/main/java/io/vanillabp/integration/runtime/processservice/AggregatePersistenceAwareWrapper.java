package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

@RequiredArgsConstructor
public class AggregatePersistenceAwareWrapper<A> implements io.vanillabp.integration.adapter.spi.AggregatePersistenceAware<A> {

  @Delegate
  private final AggregatePersistenceAware<A> delegate;

}
