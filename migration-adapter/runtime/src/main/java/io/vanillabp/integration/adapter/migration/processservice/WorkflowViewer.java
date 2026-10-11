package io.vanillabp.integration.adapter.migration.processservice;

import java.io.InputStream;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.WorkflowHistory;
import io.vanillabp.spi.process.WorkflowNotFoundException;
import lombok.extern.slf4j.Slf4j;

/**
 * The READING half of a process service: what a viewer shows about a workflow - the process
 * definitions it uses, their BPMN XML, and its execution history.
 * <p>
 * A read is unlike every operation which advances a workflow, in three ways which is why it
 * has a place of its own. Nothing is saved: the aggregate is not written back, and no outbox
 * entry is planned. A workflow which has ENDED is a perfectly valid subject, since a viewer
 * shows ended workflows, so {@link WorkflowAwareness#COMPLETED} elects the adapter answering
 * it instead of becoming the warned no-op it is elsewhere. And a read is the one caller
 * which really waits: an operation is planned in phase one and asks again at dispatch time,
 * while nothing repeats a read later - it answers the caller or it fails.
 * <p>
 * The definition ids handed out are namespaced with the answering adapter's id (see
 * {@link ProcessDefinitionIds}), because {@link #getBpmnXml(String)} has no aggregate to
 * elect by and the id is what has to say who can resolve it.
 *
 * @param <A> The workflow-aggregate type of the process service this viewer belongs to
 */
@Slf4j
public final class WorkflowViewer<A> {

  private final String workflowModuleId;

  private final String bpmnProcessId;

  /**
   * The adapter ids in priority order - named by every message about a workflow no BPMS
   * knows.
   */
  private final List<String> prioritizedAdapters;

  private final List<MigratableProcessService<A>> adapterProcessServices;

  private final AggregatePersistenceAware<A> aggregatePersistenceSupport;

  private final WorkflowLocator workflowLocator;

  /**
   * What a probe is asked about. A supplier because the platform integration sets the
   * served BPMN processes after the process service was built.
   */
  private final Supplier<WorkflowScope> scope;

  /**
   * Reads what VanillaBP wrote down about a workflow: the start row and the BPMS' own id. A
   * function because the process service owns the rows, and the operations read them the same
   * way.
   */
  private final Function<Object, WorkflowLocator.WrittenDown> writtenDownAbout;

  /**
   * Built by the {@link MigrationProcessService} of this BPMN process, in its constructor,
   * and used by nobody else. It is handed the parts it reads from rather than the process
   * service itself, which keeps the reading half away from everything that changes a
   * workflow.
   *
   * @param workflowModuleId The workflow module of this process service
   * @param bpmnProcessId The plain BPMN process id of this process service
   * @param prioritizedAdapters The adapter ids to walk, most preferred first
   * @param adapterProcessServices The adapters' process services, in the same order as
   *          <code>prioritizedAdapters</code>
   * @param aggregatePersistenceSupport How the id of a workflow aggregate is read
   * @param workflowLocator The election, shared with the process service so that a read
   *          profits from what an operation learned and the other way round
   * @param scope Reports what this process service serves right now, asked at every probe
   *          because the platform fills it in after the process service was built
   * @param writtenDownAbout Reads the start row and the BPMS' own id of the workflow of an
   *          aggregate, the same way the election of an operation reads them
   */
  public WorkflowViewer(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> prioritizedAdapters,
      final List<MigratableProcessService<A>> adapterProcessServices,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final WorkflowLocator workflowLocator,
      final Supplier<WorkflowScope> scope,
      final Function<Object, WorkflowLocator.WrittenDown> writtenDownAbout) {

    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.prioritizedAdapters = prioritizedAdapters;
    this.adapterProcessServices = adapterProcessServices;
    this.aggregatePersistenceSupport = aggregatePersistenceSupport;
    this.workflowLocator = workflowLocator;
    this.scope = scope;
    this.writtenDownAbout = writtenDownAbout;

  }

  /**
   * The process definitions used by the workflow of the given aggregate.
   *
   * @param workflowAggregate The workflow aggregate
   * @param historyContext <code>null</code> for the primary process or a secondary history
   *          context of a call activity
   * @return The process definitions
   */
  public List<ProcessDefinition> getProcessDefinitions(
      final A workflowAggregate,
      final String historyContext) {

    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(workflowAggregate);
    final var location = locateForReading(aggregateId, "process definitions");
    final var adapter = location.adapter();

    final var definitions = adapter.getProcessDefinitions(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, aggregateId, historyContext);
    if ((definitions == null) || definitions.isEmpty()) {
      throw new WorkflowNotFoundException(
          workflowUnknownMessage(aggregateId, adapter.getAdapterId(), "process definitions", historyContext));
    }

    return definitions
        .stream()
        .map(definition -> new ProcessDefinition(
            ProcessDefinitionIds.compose(adapter.getAdapterId(), definition.id()), definition
                .bpmnProcessId(), definition.version(), definition.usedByElements()))
        .toList();

  }

  /**
   * The BPMN XML of a process definition previously reported by
   * {@link #getProcessDefinitions(Object, String)}. The composite definition id names the
   * adapter which can resolve it - there is no aggregate to elect by.
   *
   * @param processDefinitionId The composite process definition id
   * @return The BPMN XML
   */
  public InputStream getBpmnXml(
      final String processDefinitionId) {

    final var parsed = ProcessDefinitionIds.parse(processDefinitionId);
    if (parsed == null) {
      throw new ProcessDefinitionNotFoundException(
          ("The process definition id '%s' does not follow VanillaBP's scheme "
              + "'<adapter id>%s<BPMS specific id>'! Pass an id reported by getProcessDefinitions "
              + "(or WorkflowHistory#processDefinitionId) of BPMN process '%s' of workflow module "
              + "'%s' unchanged - it is opaque to the application.")
              .formatted(
                  processDefinitionId,
                  ProcessDefinitionIds.SEPARATOR,
                  bpmnProcessId,
                  workflowModuleId));
    }

    final var adapter = adapterProcessServices
        .stream()
        .filter(processService -> processService.getAdapterId().equals(parsed.adapterId()))
        .findFirst()
        .orElseThrow(() -> new ProcessDefinitionNotFoundException(
            ("The process definition id '%s' addresses the adapter '%s' which is not (or no longer) "
                + "configured for BPMN process '%s' of workflow module '%s' (configured adapters, "
                + "in prioritized order: %s)! Either the id was kept from an earlier configuration "
                + "or it belongs to another workflow.")
                .formatted(
                    processDefinitionId,
                    parsed.adapterId(),
                    bpmnProcessId,
                    workflowModuleId,
                    prioritizedAdapters)));

    final var bpmnXml = adapter.getBpmnXml(
        workflowModuleId, bpmnProcessId, parsed.nativeProcessDefinitionId());
    if (bpmnXml == null) {
      throw new ProcessDefinitionNotFoundException(
          ("The adapter '%s' does not know the process definition '%s' (of BPMN process '%s' of "
              + "workflow module '%s')! Likely causes: the definition was deleted in the BPMS, or "
              + "the id was kept from a previous deployment the BPMS no longer holds.")
              .formatted(
                  parsed.adapterId(),
                  parsed.nativeProcessDefinitionId(),
                  bpmnProcessId,
                  workflowModuleId));
    }
    return bpmnXml;

  }

  /**
   * The execution history of the workflow of the given aggregate - same election and
   * read-only semantics as {@link #getProcessDefinitions(Object, String)}.
   *
   * @param workflowAggregate The workflow aggregate
   * @param historyContext <code>null</code> for the primary process or a secondary history
   *          context of a call activity
   * @return The workflow history
   */
  public WorkflowHistory getWorkflowHistory(
      final A workflowAggregate,
      final String historyContext) {

    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(workflowAggregate);
    final var location = locateForReading(aggregateId, "the workflow history");
    final var adapter = location.adapter();

    final var history = adapter.getWorkflowHistory(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, aggregateId, historyContext);
    if (history == null) {
      throw new WorkflowNotFoundException(
          workflowUnknownMessage(aggregateId, adapter.getAdapterId(), "the workflow history", historyContext));
    }

    return new WorkflowHistory(
        ProcessDefinitionIds.compose(adapter.getAdapterId(), history.processDefinitionId()), history
            .startTime(), history.endTime(), history.elementsHistory());

  }

  /**
   * Elects the adapter answering a READ. Unlike operations advancing a workflow,
   * {@link WorkflowAwareness#COMPLETED} is a regular result here (an ended workflow still
   * has definitions and a history); only a subject unknown to EVERY adapter raises the
   * SPI's {@code WorkflowNotFoundException}.
   * <p>
   * A read is the caller which has to do its own waiting. An operation advancing a
   * workflow is planned in phase one and waits for an eventually consistent read model
   * in the dispatch, where no transaction is open (decision 27 in the repository's
   * DECISIONS.md); a read has no second place to wait in - it answers the caller or it
   * fails, and a failure is not repeated by anybody. So where a hint says which adapter
   * holds the workflow, the read waits out that adapter's
   * {@code workflowVisibilityDelay}: asking for the history of a workflow the same
   * application started seconds ago is the ordinary case, and the one to three seconds
   * Camunda 8's exporter lags behind must not turn it into an error. Without a hint
   * nothing is waited for - a workflow nobody has ever seen fails at once.
   * <p>
   * The cache is not the only hint. It lives in the memory of one node, so on a second node,
   * after a restart and once it forgot, it knows nothing about a workflow started a moment ago.
   * So the read first looks at what VanillaBP wrote down, the same way an operation does: the
   * row written when the workflow started names the adapter and the id. Where there is no such
   * row, the rows of open tasks may still give the id. A start row counts as a hint, so the read
   * waits for that adapter just as it does with a cached hint (decision 112 in the repository's
   * DECISIONS.md). Without a row and without a cached hint it fails at once, as before.
   */
  private WorkflowLocator.Location<A> locateForReading(
      final Object aggregateId,
      final String subjectOfRead) {

    final var subject = "workflow of aggregate '%s' (BPMN process '%s' of workflow module '%s')"
        .formatted(aggregateId, bpmnProcessId, workflowModuleId);

    // the hint is what buys the waiting: it says the workflow exists, so an adapter not
    // reporting it yet is asked again until its visibility window is used up. Nothing
    // repeats a read later, so this is the only place it can happen. The start row is a
    // hint too, and the one a node gets which never saw the workflow
    final var writtenDown = writtenDownAbout.apply(aggregateId);
    final var workflowId = writtenDown.workflowId();
    final var location = workflowLocator.locate(
        adapterProcessServices,
        // the id VanillaBP holds rides along: this read waits out the visibility window of
        // an eventually consistent BPMS, and an adapter which can ask its engine by key
        // answers before its read model does
        adapter -> adapter
            .awarenessOfWorkflow(scope.get(), aggregatePersistenceSupport, aggregateId, workflowId),
        aggregateId,
        subject,
        WorkflowLocator.Patience.WAIT_FOR_VISIBILITY,
        writtenDown.recordedStart());

    if (location.awareness() == WorkflowAwareness.UNKNOWN_TO_BPMS) {
      throw new WorkflowNotFoundException(
          ("No configured BPMS knows the %s - %s cannot be determined (probed adapters, in "
              + "prioritized order: %s)! Likely causes: the workflow was never started, was "
              + "started through another system, or its history was already cleaned up in the "
              + "BPMS.%s")
              .formatted(
                  subject,
                  subjectOfRead,
                  prioritizedAdapters,
                  location.isUnknownButExpected()
                      ? (" The adapter '%s' was expected to hold this workflow (VanillaBP started "
                          + "it there or was handed a delivery for it) and still did not report it "
                          + "after its workflowVisibilityDelay had passed - if that BPMS answers "
                          + "from a read model, its exporter is behind or has stopped.")
                          .formatted(location.hintedAdapterId())
                      : ""));
    }
    return location;

  }

  /**
   * What a BPMS which reports the workflow but has no data for it is answered with.
   */
  private String workflowUnknownMessage(
      final Object aggregateId,
      final String adapterId,
      final String subjectOfRead,
      final String historyContext) {

    return ("The adapter '%s' cannot provide %s of the workflow of aggregate '%s' (BPMN process "
        + "'%s' of workflow module '%s'%s)! The BPMS reported the workflow as known but has no "
        + "data for it - for BPMS cleaning up history this means the retention period has "
        + "passed; for eventually consistent BPMS it may also mean the data is not yet "
        + "visible.")
        .formatted(
            adapterId,
            subjectOfRead,
            aggregateId,
            bpmnProcessId,
            workflowModuleId,
            historyContext == null
                ? ""
                : ", history context '%s'".formatted(historyContext));

  }

}
