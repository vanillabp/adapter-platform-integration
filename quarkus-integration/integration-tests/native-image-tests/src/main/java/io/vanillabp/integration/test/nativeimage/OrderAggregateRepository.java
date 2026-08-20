package io.vanillabp.integration.test.nativeimage;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The Panache repository VanillaBP picks the aggregate persistence from (story 69), which
 * is what makes the application ask for the transaction coverage of a relational store.
 */
@ApplicationScoped
public class OrderAggregateRepository implements PanacheRepositoryBase<OrderAggregate, String> {
}
