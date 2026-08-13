package io.vanillabp.integration.adapter.migration.workflowend;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.transaction.TransactionRunner;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;

/**
 * The <code>&#64;WorkflowEnded</code> methods of the application per (workflow
 * module, BPMN process). Backs the adapter-facing
 * {@link io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker},
 * which the workflow-task registry implements by delegating here.
 */
public class WorkflowEndedHandlers {

  private static final Logger log = LoggerFactory.getLogger(WorkflowEndedHandlers.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  private final Map<RegistryKey, List<WorkflowEndedHandler>> handlers = new ConcurrentHashMap<>();

  /**
   * Scans a workflow service class and registers what it found. Called by the
   * platform integration at startup, once per (workflow service class, declared
   * BPMN process ID).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowAggregateClass The workflow-aggregate class
   * @param workflowServiceBean Supplies the bean instance of that class
   */
  public void registerWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean) {

    final var scanned = WorkflowEndedScanner
        .scan(workflowServiceClass, workflowAggregateClass, workflowServiceBean);
    if (scanned.isEmpty()) {
      return;
    }
    final var registered = handlers
        .computeIfAbsent(new RegistryKey(workflowModuleId, bpmnProcessId), key -> new LinkedList<>());
    synchronized (registered) {
      scanned
          .forEach(handler -> {
            failOnDuplicateWiring(workflowModuleId, bpmnProcessId, registered, handler);
            registered.add(handler);
          });
    }

  }

  private static void failOnDuplicateWiring(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<WorkflowEndedHandler> registered,
      final WorkflowEndedHandler handler) {

    final var duplicate = registered
        .stream()
        .filter(existing -> java.util.Objects.equals(existing.getEndEventId(), handler.getEndEventId()))
        .filter(existing -> existing.matchesVersion(null) && handler.matchesVersion(null))
        .findFirst();
    if (duplicate.isPresent()) {
      throw new IllegalStateException(
          """
              The @WorkflowEnded methods '%s' and '%s' both serve %s of BPMN process '%s' of workflow \
              module '%s'! Remove one of them, name the end events they serve by \
              @WorkflowEnded(id = ...) or distinguish them by version."""
              .formatted(
                  duplicate.get().describe(),
                  handler.describe(),
                  handler.describeWiring(),
                  bpmnProcessId,
                  workflowModuleId));
    }

  }

  /**
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return Whether the application wants to be told about the end of workflows of
   *         that process
   */
  public boolean handlerExists(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return handlers.containsKey(new RegistryKey(workflowModuleId, bpmnProcessId));

  }

  /**
   * Tells the application that a workflow ended: loads the aggregate, calls the
   * method and saves the aggregate, in one transaction.
   *
   * @param <A> The workflow-aggregate type
   * @param processService The process service of the BPMN process
   * @param context The adapter's notification
   * @param transactionRunner The platform's transaction runner
   */
  public <A> void workflowEnded(
      final MigrationProcessService<A> processService,
      final WorkflowEndedContext context,
      final TransactionRunner transactionRunner) {

    final var registered = handlers
        .get(
            new RegistryKey(processService.getWorkflowModuleId(), processService.getBpmnProcessId()));
    if (registered == null) {
      // the adapter attached a listener although nothing is registered - possible
      // when a deployed model outlives the workflow service which asked for it
      log
          .debug(
              "No @WorkflowEnded method for BPMN process '{}' of workflow module '{}' - the end of "
                  + "workflow '{}' is not reported to the application",
              processService.getBpmnProcessId(),
              processService.getWorkflowModuleId(),
              context.getWorkflowAggregateId());
      return;
    }

    final var handler = registered
        .stream()
        .filter(candidate -> candidate.matchesEndEvent(context.getEndEventId()))
        .filter(candidate -> candidate.matchesVersion(context.getProcessVersion()))
        .findFirst()
        .orElse(null);
    if (handler == null) {
      log
          .debug(
              "No @WorkflowEnded method of BPMN process '{}' (workflow module '{}') serves end event "
                  + "'{}' of process version '{}' - the end of workflow '{}' is not reported",
              processService.getBpmnProcessId(),
              processService.getWorkflowModuleId(),
              context.getEndEventId(),
              context.getProcessVersion(),
              context.getWorkflowAggregateId());
      return;
    }

    final Supplier<Void> transactionalWork = () -> {
      final var workflowAggregate = processService
          .loadWorkflowAggregate(context.getWorkflowAggregateId());
      if (workflowAggregate == null) {
        // NOT an error: an application may delete the aggregate of a workflow which
        // ended, and a redelivered notification would find nothing either
        log
            .info(
                "The workflow aggregate '{}' of BPMN process '{}' (workflow module '{}') does not "
                    + "exist (any more) - the end of that workflow is not reported to '{}'",
                context.getWorkflowAggregateId(),
                processService.getBpmnProcessId(),
                processService.getWorkflowModuleId(),
                handler.describe());
        return null;
      }
      handler.invoke(workflowAggregate, context);
      processService.saveWorkflowAggregate(workflowAggregate);
      return null;
    };

    if (context.runInCurrentTransaction()) {
      transactionRunner.inCurrent(transactionalWork);
    } else {
      transactionRunner.requireNew(transactionalWork);
    }

  }

}
