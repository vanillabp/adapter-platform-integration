package io.vanillabp.integration.adapter.migration.workflowend;

import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * The <code>&#64;WorkflowEnded</code> methods of the application, per workflow module and
 * BPMN process. This class does the work behind the adapter-facing
 * {@link io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker}. The
 * workflow-task registry implements that interface and hands every call on to here.
 */
public class WorkflowEndedHandlers {

  private static final Logger log = LoggerFactory.getLogger(WorkflowEndedHandlers.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  private final Map<RegistryKey, List<WorkflowEndedHandler>> handlers = new ConcurrentHashMap<>();

  /**
   * What the BPMS know about the deployed versions of the BPMN processes. The
   * {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}
   * owns it and shares it, because all three annotations have a <code>version</code>
   * attribute.
   */
  private final io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions processVersions;

  /**
   * Built by the workflow-task registry, once per application. The versions are the
   * registry's own. A <code>&#64;WorkflowEnded</code> method names its version the same way
   * as a <code>&#64;WorkflowTask</code> method, and both have to read the same answer.
   *
   * @param processVersions What the BPMS know about the deployed versions of the BPMN
   *          processes
   */
  public WorkflowEndedHandlers(
      final io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions processVersions) {

    this.processVersions = processVersions;

  }

  /**
   * The &#64;WorkflowEnded methods registered for that BPMN process. Each comes with the
   * verdict whether its version specifications are worth keeping. A method which is worth
   * keeping nowhere in its workflow module never runs, and the start says so. The registry
   * decides that, because a method is registered once for each BPMN process its class
   * declares.
   * <p>
   * The caller decides what "worth keeping" means, and there are two answers. One is the
   * versions which the BPMS holds, minus the ones the configuration faded out. The other,
   * where the BPMS keeps no list of versions at all, is whether a delivery there can match
   * the specification.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param serves Whether a method's version specifications are worth keeping
   * @return One verdict per registered method
   */
  public List<io.vanillabp.integration.adapter.migration.workflowtask.HandlerVersions> handlerVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.function.Predicate<io.vanillabp.integration.adapter.migration.workflowtask.ServedVersions> serves) {

    final var registered = handlers.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (registered == null) {
      return List.of();
    }
    return registered
        .stream()
        .map(handler -> new io.vanillabp.integration.adapter.migration.workflowtask.HandlerVersions(
            handler.describe(), "@WorkflowEnded method '%s' (version %s)"
                .formatted(handler.describe(),
                    handler.describeVersionsWithOrigin()), serves.test(handler.servedVersions())))
        .toList();

  }

  /**
   * Scans a workflow service class and registers what it found. The platform integration
   * calls it at startup, once for each pair of workflow service class and declared BPMN
   * process ID.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowAggregateClass The workflow-aggregate class
   * @param workflowServiceBean Supplies the bean instance of that class
   * @param inherited The range the <code>&#64;BpmnProcess</code> of this
   *          process declares, which a method naming none serves
   */
  public void registerWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final io.vanillabp.integration.adapter.migration.workflowtask.InheritedVersions inherited) {

    final var scanned = WorkflowEndedScanner
        .scan(workflowServiceClass, workflowAggregateClass, workflowServiceBean, inherited);
    if (scanned.isEmpty()) {
      return;
    }
    final var registered = handlers
        .computeIfAbsent(new RegistryKey(workflowModuleId, bpmnProcessId), key -> new LinkedList<>());
    synchronized (registered) {
      scanned
          .forEach(handler -> {
            failOnDuplicateWiring(
                workflowModuleId,
                bpmnProcessId,
                registered,
                handler,
                io.vanillabp.integration.adapter.migration.workflowtask.VersionRange.NO_RESOLVER);
            registered.add(handler);
          });
    }

  }

  /**
   * Resolves the version tags which the methods of the given workflow module name. Then it
   * checks the version ranges which name a tag for overlaps. Those could not be checked at
   * registration, because no BPMS had been asked about its versions yet.
   *
   * @param workflowModuleId The workflow module ID
   */
  public void resolveProcessVersions(
      final String workflowModuleId) {

    handlers
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .forEach(entry -> {
          final var bpmnProcessId = entry.getKey().bpmnProcessId();
          final var registered = entry.getValue();
          if (registered.stream().allMatch(handler -> handler.versionTags().isEmpty())) {
            return;
          }
          processVersions.warmUp(workflowModuleId, bpmnProcessId);
          final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
          synchronized (registered) {
            registered
                .forEach(handler -> handler
                    .versionTags()
                    .forEach(tag -> processVersions.reportUnknownVersionTag(
                        workflowModuleId,
                        bpmnProcessId,
                        tag,
                        "method '%s'%s"
                            .formatted(handler.describe(), handler.describeVersionOrigin()))));
            registered
                .forEach(handler -> failOnDuplicateWiring(
                    workflowModuleId,
                    bpmnProcessId,
                    registered,
                    handler,
                    resolver));
          }
        });

  }

  private static void failOnDuplicateWiring(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<WorkflowEndedHandler> registered,
      final WorkflowEndedHandler handler,
      final io.vanillabp.integration.adapter.migration.workflowtask.VersionRange.ProcessVersionResolver resolver) {

    final var duplicate = registered
        .stream()
        .filter(existing -> existing != handler)
        .filter(existing -> java.util.Objects.equals(existing.getEndEventId(), handler.getEndEventId()))
        // overlapping version ranges are ambiguous. Ranges which do not overlap are a
        // valid way to serve several process versions
        .filter(existing -> existing.overlapsVersions(handler, resolver))
        .findFirst();
    if (duplicate.isPresent()) {
      throw new IllegalStateException(
          """
              The @WorkflowEnded methods '%s' (version %s) and '%s' (version %s) both serve %s of \
              BPMN process '%s' of workflow module '%s'! Remove one of them, name the end events they \
              serve by @WorkflowEnded(id = ...) or distinguish them by version - on the method by \
              @WorkflowEnded(version = ...), or, where a whole class serves one generation of the \
              model, on the class by @BpmnProcess(version = ...)."""
              .formatted(
                  duplicate.get().describe(),
                  duplicate.get().describeVersionsWithOrigin(),
                  handler.describe(),
                  handler.describeVersionsWithOrigin(),
                  handler.describeWiring(),
                  bpmnProcessId,
                  workflowModuleId));
    }

  }

  /**
   * Whether a method of the application was registered for that process. The registry asks
   * this while an adapter wires a model. It answers for the application's methods only. Two
   * settings want the same notification without a method, so the registry adds those itself
   * ({@code WorkflowTaskRegistry#workflowEndedHandlerExists}).
   *
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
   * Tells the application that a workflow ended. It loads the aggregate, calls the method
   * and saves the aggregate, all in one transaction. A workflow module may release the
   * records of its processed task deliveries. Then that deletion runs in the same
   * transaction, and this runs even without a <code>&#64;WorkflowEnded</code> method. That
   * is why the adapters attach their listener in that case as well.
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

    // the notification proves which BPMS held this workflow, and that the workflow is over.
    // So the hint is marked as ended instead of refreshed. It is still read while an
    // operation may arrive, and it leaves the cache long before the hint of a running one
    processService.rememberWorkflowEnded(context.getWorkflowAggregateId(), context.getAdapterId());

    final var releaseRecords = processService.releasesDeliveryRecordsOnWorkflowEnd();
    // the time limit of the release, taken BEFORE anything is done. An aggregate may outlive
    // its workflow and get a second one. The records of that second workflow are written
    // after this moment and have to survive
    final var recordedBefore = Instant.now();

    final var handler = handlerOf(processService, context);
    if ((handler == null) && !releaseRecords) {
      return;
    }

    final Supplier<Void> transactionalWork = () -> {
      if (handler != null) {
        invokeHandler(processService, context, handler);
      }
      if (releaseRecords) {
        processService.releaseDeliveryRecords(context.getWorkflowAggregateId(), recordedBefore);
      }
      return null;
    };

    io.vanillabp.integration.adapter.migration.transaction.AggregateWrite
        .inTransaction(
            transactionRunner,
            io.vanillabp.integration.adapter.migration.transaction.TransactionForm
                .askedForBy(context.runInCurrentTransaction()),
            processService.getWorkflowModuleId(),
            processService.getBpmnProcessId(),
            context.getWorkflowAggregateId(),
            handler != null
                ? "reporting the end of the workflow"
                : "releasing the task-delivery records of the ended workflow",
            transactionalWork);

  }

  /**
   * Loads the aggregate, calls the method and saves the aggregate. This is the part of the
   * end notification which belongs to the application.
   */
  private static <A> void invokeHandler(
      final MigrationProcessService<A> processService,
      final WorkflowEndedContext context,
      final WorkflowEndedHandler handler) {

    final var workflowAggregate = processService
        .loadWorkflowAggregate(context.getWorkflowAggregateId());
    if (workflowAggregate == null) {
      // NOT an error. An application may delete the aggregate of a workflow which ended,
      // and a notification delivered again would find nothing either
      log
          .info(
              "The workflow aggregate '{}' of BPMN process '{}' (workflow module '{}') does not "
                  + "exist (any more) - the end of that workflow is not reported to '{}'",
              context.getWorkflowAggregateId(),
              processService.getBpmnProcessId(),
              processService.getWorkflowModuleId(),
              handler.describe());
      return;
    }
    handler.invoke(workflowAggregate, context);
    processService.saveWorkflowAggregate(workflowAggregate);

  }

  /**
   * The <code>&#64;WorkflowEnded</code> method which serves this notification, or
   * <code>null</code> if the application has none. The log says so in that case. A method
   * wired to the event but excluded by its version is a defect the developer has to see.
   */
  private <A> WorkflowEndedHandler handlerOf(
      final MigrationProcessService<A> processService,
      final WorkflowEndedContext context) {

    final var registered = handlers
        .get(
            new RegistryKey(processService.getWorkflowModuleId(), processService.getBpmnProcessId()));
    if (registered == null) {
      // the adapter attached a listener although nothing is registered. That happens when
      // a deployed model outlives the workflow service which asked for it. It is the normal
      // case where the listener is there to release the delivery records
      log
          .debug(
              "No @WorkflowEnded method for BPMN process '{}' of workflow module '{}' - the end of "
                  + "workflow '{}' is not reported to the application",
              processService.getBpmnProcessId(),
              processService.getWorkflowModuleId(),
              context.getWorkflowAggregateId());
      return null;
    }

    final var wired = registered
        .stream()
        .filter(candidate -> candidate.matchesEndEvent(context.getEndEventId()))
        .toList();
    final var handler = wired
        .stream()
        .filter(candidate -> candidate.matchesVersion(
            context.getProcessVersion(),
            processVersions.resolverFor(
                processService.getWorkflowModuleId(),
                processService.getBpmnProcessId())))
        .findFirst()
        .orElse(null);
    if (handler != null) {
      return handler;
    }

    // a method wired to the event but excluded by its version is worth a warning. The
    // application asked to be notified and is not, and no log level should hide that
    final var message = "No @WorkflowEnded method of BPMN process '{}' (workflow module '{}') "
        + "serves end event '{}' of process version '{}' - the end of workflow '{}' is not "
        + "reported.{}";
    final var hint = io.vanillabp.integration.adapter.migration.workflowtask.VersionRange
        .noVersionReportedHint(
            context.getProcessVersion(),
            wired.stream().anyMatch(WorkflowEndedHandler::inheritsVersions));
    if (wired.isEmpty()) {
      log
          .debug(
              message,
              processService.getBpmnProcessId(),
              processService.getWorkflowModuleId(),
              context.getEndEventId(),
              context.getProcessVersion(),
              context.getWorkflowAggregateId(),
              hint);
    } else {
      log
          .warn(
              message,
              processService.getBpmnProcessId(),
              processService.getWorkflowModuleId(),
              context.getEndEventId(),
              context.getProcessVersion(),
              context.getWorkflowAggregateId(),
              hint);
    }
    return null;

  }

}
