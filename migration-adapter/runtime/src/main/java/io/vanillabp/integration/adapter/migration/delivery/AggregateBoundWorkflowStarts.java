package io.vanillabp.integration.adapter.migration.delivery;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.WorkflowStartSieve;
import lombok.extern.slf4j.Slf4j;

/**
 * The second sieve in front of deleting a workflow-start row: it asks the application's own
 * persistence whether the workflow aggregate of that row still exists.
 *
 * <strong>How the aggregate is reached</strong>
 *
 * The row names a workflow module, a BPMN process and the aggregate's id in serialized form.
 * {@link PhaseTwoRouter} is the one place where every process service of the application is
 * collected, so it answers the first two, and the process service converts the id into the
 * aggregate's own type and loads it. That is the same path a dispatched outbox entry walks, so
 * nothing new is asked of an application here.
 * <p>
 * The load runs in a transaction of its own, taken from the runner serving that aggregate: the
 * cleanup has a thread but no transaction, and an application whose aggregates live in a system
 * of its own brought that runner for exactly this kind of call.
 *
 * <strong>What makes it answer "cannot say"</strong>
 *
 * Three things, and each of them KEEPS the row. The BPMN process is not part of this application
 * any more, so nobody here can judge its aggregates. The router has not been built yet, which is
 * a cleanup running while the application still starts. And
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware#loadById} is not implemented,
 * which a custom persistence answers with an {@link UnsupportedOperationException} - reading that
 * as "the aggregate is gone" would delete exactly the rows nobody can write again. It is said
 * once and not once per run, because a cleanup which runs hourly for a year would otherwise say it
 * eight thousand times.
 */
@Slf4j
public class AggregateBoundWorkflowStarts implements WorkflowStartSieve {

  private final Supplier<PhaseTwoRouter> router;

  private final String storeName;

  /**
   * Whether the message about a persistence which cannot load by id was written already.
   */
  private final AtomicBoolean cannotLoadReported = new AtomicBoolean();

  /**
   * Built by the platform integration where the application asked for the sieve
   * (<code>vanillabp.delivery.keep-workflow-start-while-aggregate-exists</code>).
   *
   * @param router Where the process services are collected, resolved when the sieve is first
   *          asked rather than now: the router is a bean like the store which uses this, and
   *          asking for it while the beans are being built would be too early. May answer
   *          <code>null</code>
   * @param storeName The table or collection this sieve guards, named by its messages
   */
  public AggregateBoundWorkflowStarts(
      final Supplier<PhaseTwoRouter> router,
      final String storeName) {

    this.router = router;
    this.storeName = storeName;

  }

  @Override
  public Boolean mayBeDeleted(
      final TaskDelivery workflowStart) {

    final var registry = router == null
        ? null
        : router.get();
    if (registry == null) {
      return null;
    }
    final var processService = registry
        .processServiceOf(workflowStart.workflowModuleId(), workflowStart.bpmnProcessId());
    if (processService == null) {
      log.debug(
          "The row about the start of workflow '{}' names BPMN process '{}' of workflow module "
              + "'{}', which this application does not serve - the row stays in '{}' until its "
              + "period passed for a second time",
          workflowStart.workflowId(),
          workflowStart.bpmnProcessId(),
          workflowStart.workflowModuleId(),
          storeName);
      return null;
    }
    final var runner = processService.getTransactionRunner(null);
    try {
      final var aggregate = runner == null
          ? processService.loadWorkflowAggregate(workflowStart.workflowAggregateId())
          : runner
              .requireNew(() -> processService.loadWorkflowAggregate(workflowStart.workflowAggregateId()));
      return aggregate == null;
    } catch (final UnsupportedOperationException e) {
      reportThatTheAggregateCannotBeLoaded(processService.getWorkflowAggregateClass(), e);
      return null;
    } catch (final RuntimeException e) {
      log.warn(
          "Could not read whether the workflow aggregate '{}' of BPMN process '{}' (workflow "
              + "module '{}') still exists - the row about the start of its workflow stays in '{}'",
          workflowStart.workflowAggregateId(),
          workflowStart.bpmnProcessId(),
          workflowStart.workflowModuleId(),
          storeName,
          e);
      return null;
    }

  }

  /**
   * Says once that the persistence of an aggregate cannot be asked whether an aggregate exists, so
   * the sieve the application switched on does nothing for those rows and the period alone decides
   * them.
   *
   * @param workflowAggregateClass The aggregate whose persistence cannot load by id
   * @param cause What the persistence answered, which names the implementation
   */
  private void reportThatTheAggregateCannotBeLoaded(
      final Class<?> workflowAggregateClass,
      final UnsupportedOperationException cause) {

    if (!cannotLoadReported.compareAndSet(false, true)) {
      return;
    }
    log.warn(
        """
            '{}' is switched on, but the workflow aggregate '{}' cannot be loaded by its id, so \
            VanillaBP cannot tell whether such an aggregate still exists. The rows about the \
            started workflows of that aggregate are therefore KEPT in '{}' rather than deleted on \
            a guess - which means they are never deleted. Either implement \
            io.vanillabp.integration.spi.AggregatePersistenceAware#loadById for this aggregate, or \
            set '{}' to 'false' and let '{}' decide alone. This is said once per store.""",
        io.vanillabp.integration.adapter.migration.config.DeliveryProperties.KEEP_WORKFLOW_START_WHILE_AGGREGATE_EXISTS_PROPERTY,
        workflowAggregateClass.getName(),
        storeName,
        io.vanillabp.integration.adapter.migration.config.DeliveryProperties.KEEP_WORKFLOW_START_WHILE_AGGREGATE_EXISTS_PROPERTY,
        io.vanillabp.integration.adapter.migration.config.DeliveryProperties.WORKFLOW_START_RETENTION_PROPERTY,
        cause);

  }

}
