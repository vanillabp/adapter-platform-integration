package io.vanillabp.integration.utils.impl;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * The persistence VanillaBP falls back to when no {@link AggregatePersistenceAware} bean
 * covers a workflow aggregate: everything it does, it does through a Spring Data
 * repository of that aggregate.
 * <p>
 * Which is why a missing repository is a defect rather than a variant, and why it is
 * reported while the application STARTS. Without that the application
 * booted and failed at the first task delivery or the first <code>startWorkflow</code>,
 * with Spring Data's own <code>No Spring Data repository defined for ...</code> and
 * nothing about the workflow it belonged to. Quarkus decides the same question while it
 * builds, so the same application used to fail there and start here.
 */
public class SpringDataUtilBasedAggregatePersistenceSupport<A> implements AggregatePersistenceAware<A> {

  private final SpringDataUtil springDataUtil;

  private final Class<A> aggregateClass;

  /**
   * The workflow module the aggregate belongs to, for the message alone - may be
   * <code>null</code> where the caller knows none.
   */
  private final String workflowModuleId;

  public SpringDataUtilBasedAggregatePersistenceSupport(
      final SpringDataUtil springDataUtil,
      final Class<A> aggregateClass) {

    this(springDataUtil, aggregateClass, null);

  }

  public SpringDataUtilBasedAggregatePersistenceSupport(
      final SpringDataUtil springDataUtil,
      final Class<A> aggregateClass,
      final String workflowModuleId) {

    this.springDataUtil = springDataUtil;
    this.aggregateClass = aggregateClass;
    this.workflowModuleId = workflowModuleId;

  }

  @Override
  public Class<A> getAggregateClass() {

    return aggregateClass;

  }

  @Override
  public A save(
      A aggregate) {

    return springDataUtil
        .getRepository(aggregate)
        .save(aggregate);

  }

  @Override
  public Object getAggregateId(
      A aggregate) {

    return springDataUtil
        .getId(aggregate);

  }

  @Override
  public String getAggregateIdName() {

    return springDataUtil
        .getIdName(aggregateClass);

  }

  @Override
  public Class<?> getAggregateIdType() {

    // the one method the core calls while the application starts (see the constructor of
    // MigrationProcessService), which makes it the place where a missing repository has to
    // be reported
    requireRepository();

    // Spring Data is authoritative for the ID type (covers property access,
    // non-"id"-named IDs etc.). A repository which cannot say what the ID type is answers
    // null, the contract's "not determinable": that is the layer's own business, an own
    // SpringDataUtil implementation owning the serialized form for instance. What null
    // must NOT mean is that there is no repository at all, which is a conflation this
    // check takes apart
    try {
      return springDataUtil.getIdType(aggregateClass);
    } catch (Exception e) {
      return null;
    }

  }

  @Override
  public A loadById(
      final Object aggregateId) {

    return springDataUtil
        .getRepository(aggregateClass)
        .findById(aggregateId)
        .orElse(null);

  }

  /**
   * Resolves the aggregate's repository once, so an application says what is missing
   * instead of failing at the first task.
   *
   * @throws IllegalStateException If the aggregate has no Spring Data repository
   */
  private void requireRepository() {

    try {
      springDataUtil.getRepository(aggregateClass);
    } catch (Exception e) {
      throw new IllegalStateException(
          ("""
              VanillaBP does not know how to persist the workflow aggregate '%s'%s!
              It has no Spring Data repository, and the persistence VanillaBP falls back to loads \
              and saves an aggregate through one. Either
              - add a Spring Data repository for this aggregate (a JpaRepository, a \
              MongoRepository, ...), and mind that Spring has to find it while scanning for \
              repositories,
              - or provide a bean implementing
                %s<%s>
                which is responsible to persist this aggregate,
              - or add your own implementation of io.vanillabp.integration.utils.SpringDataUtil, if \
              the aggregate is persisted by a technology of yours.""")
              .formatted(
                  aggregateClass.getName(),
                  workflowModuleId == null
                      ? ""
                      : " of workflow module '%s'".formatted(workflowModuleId),
                  AggregatePersistenceAware.class.getName(),
                  aggregateClass.getSimpleName()), e);
    }

  }

}
