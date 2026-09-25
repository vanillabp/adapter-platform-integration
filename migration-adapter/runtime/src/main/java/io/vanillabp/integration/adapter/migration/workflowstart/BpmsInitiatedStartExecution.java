package io.vanillabp.integration.adapter.migration.workflowstart;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.processservice.BusinessKeyCheck;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * Lets the APPLICATION build the workflow aggregate of a workflow the BPMS started on
 * its own, in one transaction: call the <code>&#64;WorkflowStartedByBpms</code> method,
 * take the aggregate it returns, reuse the one already carrying that id, otherwise save.
 * <p>
 * VanillaBP builds nothing itself. An object which comes into existence without the
 * application does not carry the application's values, and for a workflow the BPMS
 * started that would be the very first thing that ever happens to it.
 */
public final class BpmsInitiatedStartExecution {

  private static final Logger log = LoggerFactory.getLogger(BpmsInitiatedStartExecution.class);

  private BpmsInitiatedStartExecution() {
  }

  /**
   * Runs that build for one notification of the BPMS, in the transaction the adapter asked
   * for. A notification which arrives twice - a retried listener job, a replayed engine
   * transaction - finds the aggregate under the id the application chose and saves nothing,
   * so business data written meanwhile survives. Whether a repetition can be recognized at
   * all is therefore the application's decision: a timer brings its trigger time, a signal
   * and a condition bring nothing.
   *
   * @param <A> The workflow-aggregate type
   * @param processService The process service of the BPMN process (persistence, ID
   *          type, aggregate class)
   * @param handler The application's method building the aggregate
   * @param context The adapter's notification
   * @param transactionRunner The platform's transaction runner
   * @return The aggregate's ID and the variables the adapter writes back (the
   *         aggregate-ID variable; shared aggregate values are added by the caller)
   */
  public static <A> BpmsInitiatedStartResult run(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartHandler handler,
      final BpmsInitiatedStartContext context,
      final TransactionRunner transactionRunner) {

    final Supplier<BpmsInitiatedStartResult> transactionalWork = () -> build(
        processService,
        handler,
        context);

    // the started instance is the activation the application's handler runs in, so
    // what it plans is told apart from what a second firing of the same start event
    // plans (see io.vanillabp.integration.spi.RunningActivation)
    try (var activation = io.vanillabp.integration.spi.RunningActivation
        .of(context.getNativeInstanceId())) {
      return io.vanillabp.integration.adapter.migration.transaction.AggregateWrite
          .inTransaction(
              transactionRunner,
              io.vanillabp.integration.adapter.migration.transaction.TransactionForm
                  .askedForBy(context.runInCurrentTransaction()),
              processService.getWorkflowModuleId(),
              processService.getBpmnProcessId(),
              context.getNaturalIdentity(),
              "the BPMS-initiated start at start event '%s'".formatted(context.getStartEventId()),
              transactionalWork);
    }

  }

  private static <A> BpmsInitiatedStartResult build(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartHandler handler,
      final BpmsInitiatedStartContext context) {

    final var aggregateClass = processService.getWorkflowAggregateClass();
    final var returned = handler.invoke(context);
    if (returned == null) {
      throw new IllegalStateException(
          """
              The @WorkflowStartedByBpms method '%s' returned null! Return the workflow aggregate \
              of the workflow the BPMS started (BPMN process '%s' of workflow module '%s', start \
              event '%s') - without it the workflow has no data at all."""
              .formatted(
                  handler.describe(),
                  processService.getBpmnProcessId(),
                  processService.getWorkflowModuleId(),
                  context.getStartEventId()));
    }
    final var workflowAggregate = aggregateClass.cast(returned);

    final var chosenId = processService.getWorkflowAggregateId(workflowAggregate);
    if (chosenId != null) {
      final var existing = processService.loadWorkflowAggregateById(chosenId);
      if (existing != null) {
        // at-least-once: the BPMS reported this start before (a retried listener
        // job, a replayed engine transaction) and the application chose an id which
        // says so - saving the new object would overwrite business data
        log
            .debug(
                "The workflow aggregate '{}' of the BPMS-initiated start of BPMN process '{}' "
                    + "(workflow module '{}', start event '{}') exists already - nothing is saved",
                chosenId,
                processService.getBpmnProcessId(),
                processService.getWorkflowModuleId(),
                context.getStartEventId());
        return result(processService, existing, false, context);
      }
    }

    final var attached = processService.saveWorkflowAggregate(workflowAggregate);
    final var aggregateId = processService.getWorkflowAggregateId(attached);
    if ((aggregateId == null) || aggregateId.toString().isBlank()) {
      throw new IllegalStateException(
          """
              The ID of the workflow aggregate of class '%s' is null or blank after saving the \
              workflow the BPMS started (BPMN process '%s' of workflow module '%s', start event \
              '%s')! The ID identifies the workflow in the BPMS - assign one in the \
              @WorkflowStartedByBpms method '%s', or use a generated ID which the persistence \
              layer assigns on save."""
              .formatted(
                  aggregateClass.getName(),
                  processService.getBpmnProcessId(),
                  processService.getWorkflowModuleId(),
                  context.getStartEventId(),
                  handler.describe()));
    }
    return result(processService, attached, true, context);

  }

  /**
   * The aggregate's id and the variables the adapter writes back - and the last point at
   * which a business key the instance already carries can still be refused for free.
   * <p>
   * The id is only final here: the application may have assigned it, or the persistence
   * layer may have done so on save. Both ways out of {@code build} pass through this
   * method, and both run inside the transaction the start opened, so a refusal takes the
   * aggregate with it instead of leaving one behind which the instance does not name.
   */
  private static <A> BpmsInitiatedStartResult result(
      final MigrationProcessService<A> processService,
      final A workflowAggregate,
      final boolean created,
      final BpmsInitiatedStartContext context) {

    final var aggregateIdName = processService.getAggregateIdName();
    final var serializedId = String.valueOf(processService.getWorkflowAggregateId(workflowAggregate));
    BusinessKeyCheck
        .refuseAKeyWhichIsNotTheAggregateId(
            context.getBusinessKey(),
            serializedId,
            "The start of a workflow by start event '%s'".formatted(context.getStartEventId()),
            processService.getWorkflowModuleId(),
            processService.getBpmnProcessId(),
            context.getAdapterId(),
            context.getNativeInstanceId());
    final Map<String, Object> variables = new LinkedHashMap<>();
    variables.put(aggregateIdName, serializedId);
    return new BpmsInitiatedStartResult(serializedId, aggregateIdName, variables, created);

  }

}
