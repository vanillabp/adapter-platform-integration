package io.vanillabp.integration.test.nativeimage;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The Panache repository from which VanillaBP picks the aggregate persistence. Because of
 * it, the application asks for the transaction coverage of a relational store.
 */
@ApplicationScoped
public class OrderAggregateRepository implements PanacheRepositoryBase<OrderAggregate, String> {
}
