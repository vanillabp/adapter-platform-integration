package io.vanillabp.integration.test.deployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the task-processing acceptance test's aggregate.
 * Aggregates are copied on save/load so un-saved mutations never leak into the
 * store - mimicking a real persistence where only saved state survives.
 */
@ApplicationScoped
public class TaskAggregatePersistence implements AggregatePersistenceAware<TaskAggregate> {

  private final Map<String, TaskAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<TaskAggregate> getAggregateClass() {

    return TaskAggregate.class;

  }

  @Override
  public TaskAggregate save(
      final TaskAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final TaskAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public TaskAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  /**
   * Direct store access for test assertions (only saved state is visible).
   *
   * @param id The aggregate's ID
   * @return The stored aggregate or <code>null</code>
   */
  public TaskAggregate stored(
      final String id) {

    return aggregates.get(id);

  }

  /**
   * Seeds an aggregate into the store, as if a workflow had been started.
   *
   * @param id The aggregate's ID
   * @return The seeded aggregate
   */
  public TaskAggregate seed(
      final String id) {

    final var aggregate = new TaskAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    aggregates.put(id, aggregate);
    return aggregate;

  }

  private static TaskAggregate copyOf(
      final TaskAggregate aggregate) {

    final var copy = new TaskAggregate();
    copy.setId(aggregate.getId());
    copy.setStatus(aggregate.getStatus());
    copy.setTaskId(aggregate.getTaskId());
    copy.setEvent(aggregate.getEvent());
    copy.setElement(aggregate.getElement());
    copy.setIndex(aggregate.getIndex());
    copy.setTotal(aggregate.getTotal());
    copy.setRequestScopedProbe(aggregate.getRequestScopedProbe());
    return copy;

  }

}
