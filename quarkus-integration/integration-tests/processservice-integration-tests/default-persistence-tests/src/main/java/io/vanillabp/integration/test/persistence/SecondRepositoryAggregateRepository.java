package io.vanillabp.integration.test.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A second Panache repository for {@link RepositoryAggregate}. The test uses it to prove
 * that VanillaBP does not pick one of two repositories at random.
 */
@ApplicationScoped
public class SecondRepositoryAggregateRepository implements PanacheRepositoryBase<RepositoryAggregate, String> {
}
