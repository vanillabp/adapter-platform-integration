package io.vanillabp.integration.utils.impl;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * The persistence VanillaBP falls back to when no {@link AggregatePersistenceAware} bean
 * covers a workflow aggregate. Everything it does, it does through a Spring Data
 * repository of that aggregate.
 * <p>
 * So a missing repository is a defect, not a variant. That is why it is reported while
 * the application STARTS. Before, the application booted and failed at the first task
 * delivery or the first <code>startWorkflow</code>. The message was Spring Data's own
 * <code>No Spring Data repository defined for ...</code>, with nothing about the workflow
 * it belonged to. Quarkus decides the same question while it builds. So the same
 * application used to fail there and start here.
 * <p>
 * A workflow aggregate which did not change since it was loaded is not written again (see decision
 * 130 in the repository's DECISIONS.md). That matters where the repository replaces the whole
 * document on every save, as MongoDB does. Then each save of an unchanged aggregate would raise its
 * version, and two parallel tasks which only read it would collide. The comparison uses {@link
 * SpringDataUtil#writtenFormOf(Object)}. Where that has no answer, as for JPA, every save writes,
 * and Hibernate decides itself whether there is anything to write.
 *
 * @param <A> The workflow aggregate this instance persists. There is one instance per
 *          aggregate class, because a repository is searched for by the class
 */
public class SpringDataUtilBasedAggregatePersistenceSupport<A> implements AggregatePersistenceAware<A> {

  private final SpringDataUtil springDataUtil;

  private final Class<A> aggregateClass;

  private final FormsAtLoad formsAtLoad = new FormsAtLoad();

  /**
   * The workflow module the aggregate belongs to. It is only used for the message, and it
   * may be <code>null</code> where the caller knows none.
   */
  private final String workflowModuleId;

  /**
   * The support of an aggregate whose workflow module the caller does not name. Then the
   * message about a missing repository says less. So prefer the constructor below wherever
   * the module is known.
   *
   * @param springDataUtil How the aggregate's repository is found
   * @param aggregateClass The workflow aggregate's class, which is what the repository is
   *          searched for
   */
  public SpringDataUtilBasedAggregatePersistenceSupport(
      final SpringDataUtil springDataUtil,
      final Class<A> aggregateClass) {

    this(springDataUtil, aggregateClass, null);

  }

  /**
   * The support of an aggregate of one known workflow module. The process service
   * registrar builds this one. Then an application with several modules reads which of
   * them the aggregate without a repository belongs to.
   *
   * @param springDataUtil How the aggregate's repository is found
   * @param aggregateClass The workflow aggregate's class, which is what the repository is
   *          searched for
   * @param workflowModuleId The workflow module the aggregate belongs to. It is only used
   *          for the message. <code>null</code> where the caller knows none
   */
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

  /**
   * Writes the aggregate, unless it was loaded in the running transaction and its written
   * form did not change since. Then the loaded object is returned as it is. The form is
   * compared BEFORE anything is written, so neither the version attribute nor an auditing
   * field counts as a change: Spring Data sets both only while it writes.
   */
  @Override
  public A save(
      final A aggregate) {

    final var formBeforeWriting = springDataUtil.writtenFormOf(aggregate);
    if (formsAtLoad.isUnchanged(aggregate, formBeforeWriting)) {
      return aggregate;
    }
    final var saved = springDataUtil
        .getRepository(aggregate)
        .save(aggregate);
    // a second save in the same transaction compares against what is in the database now
    formsAtLoad.remember(saved, springDataUtil.writtenFormOf(saved));
    return saved;

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
    // MigrationProcessService). So this is where a missing repository has to be reported
    requireRepository();

    // Spring Data has the last word on the ID type. It covers property access, IDs not
    // named "id" and so on. A repository which cannot say what the ID type is answers
    // null, which is "not determinable" in the contract. That is the business of that
    // layer, e.g. an own SpringDataUtil implementation which owns the serialized form.
    // But null must NOT mean that there is no repository at all. The check above keeps
    // these two cases apart
    try {
      return springDataUtil.getIdType(aggregateClass);
    } catch (Exception e) {
      return null;
    }

  }

  /**
   * Loads the aggregate and remembers its written form for the running transaction. So a
   * later {@link #save(Object)} can tell whether anything changed.
   */
  @Override
  public A loadById(
      final Object aggregateId) {

    final var aggregate = springDataUtil
        .getRepository(aggregateClass)
        .findById(aggregateId)
        .orElse(null);
    if (aggregate != null) {
      formsAtLoad.remember(aggregate, springDataUtil.writtenFormOf(aggregate));
    }
    return aggregate;

  }

  /**
   * Looks up the aggregate's repository once. So an application learns what is missing,
   * and does not fail at the first task.
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
