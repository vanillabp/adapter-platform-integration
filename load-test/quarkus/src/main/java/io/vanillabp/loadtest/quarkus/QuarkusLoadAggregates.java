package io.vanillabp.loadtest.quarkus;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.loadharness.LoadAggregate;
import io.vanillabp.loadharness.LoadAggregates;
import jakarta.inject.Singleton;

/**
 * The aggregates of the load run as a bean class. Quarkus looks for the persistence of an
 * aggregate while it builds the application, and it finds a class there, not a producer
 * method. The class names the interface itself, because the harness jar carries no index
 * which would tell Quarkus what its superclass implements.
 */
@Singleton
public class QuarkusLoadAggregates extends LoadAggregates implements AggregatePersistenceAware<LoadAggregate> {

}
