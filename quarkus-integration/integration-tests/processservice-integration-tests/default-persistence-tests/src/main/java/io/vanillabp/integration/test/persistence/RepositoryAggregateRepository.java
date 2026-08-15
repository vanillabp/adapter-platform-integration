package io.vanillabp.integration.test.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RepositoryAggregateRepository implements PanacheRepositoryBase<RepositoryAggregate, String> {
}
