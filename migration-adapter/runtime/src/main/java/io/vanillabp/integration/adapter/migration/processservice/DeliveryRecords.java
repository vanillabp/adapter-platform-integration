package io.vanillabp.integration.adapter.migration.processservice;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.workflowtask.TaskDeliveryIdentity;
import io.vanillabp.integration.adapter.migration.workflowtask.TaskDeliveryKey;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.Election;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.startup.StartupTopic;
import lombok.extern.slf4j.Slf4j;

/**
 * What VanillaBP remembers about the task deliveries of ONE BPMN process: a record is
 * written in the transaction which also saves the workflow aggregate, and it answers three
 * questions nothing else can answer.
 * <ul>
 * <li>Was this delivery processed before? A BPMS which never learned the result of a
 * delivery hands the task out again, and the record is what keeps the
 * <code>&#64;WorkflowTask</code> method from running a second time - the recorded outcome
 * is reported instead.</li>
 * <li>How long has a task left open by a <code>&#64;TaskId</code> handler been waiting?
 * The record was written when the handler ran, so the distance between its timestamp and
 * now IS the age of the open task.</li>
 * <li>Which BPMS holds a task the application names? The record says which adapter
 * delivered it, which saves the election one round trip per operation.</li>
 * </ul>
 * Only the first of the three is the deduplication, and it used to decide whether the other
 * two were written down at all: an adapter which reports no delivery ID got no row, so
 * nobody could read back which BPMS held the task or which kind of id its id was. Such a
 * delivery now gets a row of its own which takes no part in the deduplication
 * ({@link #identityOf(TaskInvocationContext)}).
 * <p>
 * The store behind all of this is a {@link TaskDeliveryLog} of the application, resolved
 * for the workflow aggregate. There may be none: an application whose adapters never repeat
 * a delivery needs no store, and one which has not configured a store behaves as every
 * VanillaBP before the records existed - at-least-once, with the rule to key business
 * decisions on the aggregate's state carrying the case. Every method here answers that way
 * where no store is available, and {@link #validateAtStartup(List)} says once at startup
 * what is given up.
 * <p>
 * The adapters are passed in rather than held, the way {@link WorkflowLocator} takes them:
 * this collaborator is about the records, and which adapters serve a BPMN process is the
 * process service's business.
 * <p>
 * One instance belongs to one BPMN process and WRITES under that id. The two reads about a
 * task the application names walk further: a task of a secondary process was delivered to
 * the instance of THAT id, while every {@code ProcessService} call reaches the primary one,
 * so they ask for each id the workflow service serves, the own id first
 * ({@link #setBpmnProcessIdsToReadUnder(List)}).
 */
@Slf4j
public final class DeliveryRecords {

  /**
   * How many open tasks are remembered as reported. A bound rather than a growing set:
   * the entry is a hint, and forgetting one costs one repeated WARN.
   */
  private static final int REPORTED_TASK_AGES = 1000;

  private final String workflowModuleId;

  private final String bpmnProcessId;

  /**
   * The BPMN process ids a record of this workflow may sit under, this instance's own id
   * first. Set by the process service once the platform integration told it which
   * processes its workflow service serves; until then the own id is the whole list.
   */
  private volatile List<String> bpmnProcessIdsToReadUnder;

  private final Class<?> workflowAggregateClass;

  /**
   * The bound <code>vanillabp.*</code> tree - deduplication and the maximum age of an open
   * task are resolvable per workflow module, workflow and task.
   */
  private final MigrationAdapterProperties properties;

  /**
   * Resolves the store used for this aggregate. Provided by the platform integration; may
   * be <code>null</code> (tests) - deliveries are then not deduplicated.
   */
  private final TaskDeliveryLogResolver resolver;

  /**
   * The store resolved for this aggregate, <code>null</code> until resolved (at startup via
   * {@link #validateAtStartup(List)} or lazily as backstop).
   */
  private volatile TaskDeliveryLog taskDeliveryLog;

  /**
   * Whether the "deliveries are not deduplicated" message was logged already - it names a
   * configuration gap, and one line per delivery would bury it.
   */
  private final AtomicBoolean missingDeliveryLogReported = new AtomicBoolean();

  /**
   * Where a finding of these records goes, so the whole start says it once.
   */
  private final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings;

  /**
   * What the application counts about its deliveries, handed over by the process service
   * once the platform integration knows it.
   */
  private volatile VanillaBpMetrics metrics = VanillaBpMetrics.NONE;

  private final Map<String, Boolean> reportedTaskAges = Collections
      .synchronizedMap(new LinkedHashMap<String, Boolean>(16, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(
            final Map.Entry<String, Boolean> eldest) {

          return size() > REPORTED_TASK_AGES;

        }

      });

  /**
   * Built by the {@link MigrationProcessService} of this BPMN process, in its constructor.
   * The store is NOT resolved here: it is a bean of the application, and asking for it
   * while the beans are being built would be too early, so it is resolved at the first use
   * and kept from then on ({@link #resolveLog()}).
   *
   * @param workflowModuleId The workflow module the records belong to
   * @param bpmnProcessId The BPMN process the records belong to
   * @param workflowAggregateClass The workflow aggregate whose store holds the records -
   *          named by the message about a missing store
   * @param properties The bound configuration, may be <code>null</code> in tests
   * @param resolver Resolves the store for the aggregate, may be <code>null</code>
   */
  public DeliveryRecords(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final TaskDeliveryLogResolver resolver) {

    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.bpmnProcessIdsToReadUnder = List.of(bpmnProcessId);
    this.workflowAggregateClass = workflowAggregateClass;
    this.properties = properties;
    this.resolver = resolver;
    this.findings = MigrationAdapterProperties.startupFindingsOf(properties);

  }

  /**
   * Where a record of this workflow may be looked for, beyond this instance's own BPMN
   * process id.
   * <p>
   * A record is written under the id of the process which DELIVERED the task, and for a
   * task of a secondary process that is the secondary id, while the application completes
   * that task on the primary process service. Reading only the own id therefore found no
   * record for such a task: the operation paid a probe of the adapter it need not have
   * paid, and the row nobody found stayed open.
   *
   * @param bpmnProcessIds The ids to read under, the own id first; <code>null</code> or
   *          empty restores the own id alone
   */
  public void setBpmnProcessIdsToReadUnder(
      final List<String> bpmnProcessIds) {

    this.bpmnProcessIdsToReadUnder = (bpmnProcessIds == null) || bpmnProcessIds.isEmpty()
        ? List.of(bpmnProcessId)
        : List.copyOf(bpmnProcessIds);

  }

  /**
   * Hands over what to count into, done by the process service once the platform
   * integration told it. A <code>null</code> becomes {@link VanillaBpMetrics#NONE} rather
   * than an error, because an application without a metrics backend is the normal case.
   *
   * @param metrics What to count into, never <code>null</code>
   */
  public void setMetrics(
      final VanillaBpMetrics metrics) {

    this.metrics = metrics == null
        ? VanillaBpMetrics.NONE
        : metrics;

  }

  /**
   * The identity under which this delivery is remembered, or <code>null</code> where
   * nothing at all is remembered about it
   * (<code>vanillabp.adapters.&lt;id&gt;.deduplicate-deliveries</code> switched off).
   * <p>
   * The property is the one case where no row is written. It says that the handlers of this
   * application are idempotent themselves, and the BPMS behind it may well repeat a
   * delivery - every repetition would add a row naming the same task, and whoever reads the
   * open work of a workflow would read that task as many times as the BPMS handed it out.
   * An adapter which reports no delivery ID is the other case and gets a row: it repeats
   * nothing, so one delivery is one row (see
   * {@link TaskDeliveryKey#of(String, String, TaskInvocationContext)}).
   *
   * @param context The invocation context of the delivery
   * @return The identity of the delivery or <code>null</code>
   */
  public TaskDeliveryIdentity identityOf(
      final TaskInvocationContext context) {

    return deduplicates(context.getAdapterId(), context.getTaskDefinition())
        ? TaskDeliveryKey.of(workflowModuleId, bpmnProcessId, context)
        : null;

  }

  /**
   * Whether deliveries of the given adapter are deduplicated for the given task
   * (resolvable per workflow module, workflow and task). The default is
   * <code>true</code>; a delivery whose adapter is not named at all is not deduplicated,
   * since neither the configuration nor the delivery key could be attributed to a BPMS
   * then.
   *
   * @param adapterId The ID of the adapter delivering the task or <code>null</code>
   * @param taskDefinition The task definition delivered
   * @return Whether to remember this delivery
   */
  private boolean deduplicates(
      final String adapterId,
      final String taskDefinition) {

    if ((adapterId == null) || (properties == null)) {
      return false;
    }
    final var configured = properties
        .resolveForAdapter(
            workflowModuleId,
            bpmnProcessId,
            taskDefinition,
            adapterId,
            AdapterProperties::getDeduplicateDeliveries);
    return (configured == null) || configured;

  }

  /**
   * The store holding the records of this aggregate, resolved once and kept.
   *
   * @return The store or <code>null</code> where the application has none
   */
  public TaskDeliveryLog resolveLog() {

    if ((taskDeliveryLog == null) && (resolver != null)) {
      taskDeliveryLog = resolver.resolveFor(workflowAggregateClass);
    }
    return taskDeliveryLog;

  }

  /**
   * States once that deliveries of this BPMN process are not deduplicated, and how to
   * change that. Also the answer to "why did my handler run twice" - the message is the
   * first thing to look for then.
   *
   * @param adapterId The ID of an adapter which may repeat a delivery
   */
  public void reportMissingLog(
      final String adapterId) {

    if (!missingDeliveryLogReported.compareAndSet(false, true)) {
      return;
    }
    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.CODE,
            "process '%s' of workflow module '%s', adapter '%s'"
                .formatted(bpmnProcessId, workflowModuleId, adapterId),
            """
                This adapter may deliver a task of this BPMN process more than once, but no \
                TaskDeliveryLog is available for aggregate '%s' - a repeated delivery will run \
                the @WorkflowTask method again. To solve this either
                %s
                - define your own bean implementing io.vanillabp.integration.spi.TaskDeliveryLog \
                (assign it to specific aggregates via a \
                io.vanillabp.integration.spi.TaskDeliveryLogAware bean), or
                - set 'vanillabp.adapters.%s.deduplicate-deliveries' to 'false' to state that \
                the handlers of this application are idempotent themselves."""
                .formatted(
                    workflowAggregateClass.getName(),
                    resolver == null
                        ? "- provide a TaskDeliveryLogResolver (platform integration), or"
                        : resolver.remediesDescription(),
                    adapterId));

  }

  /**
   * The outcome a repeated delivery is answered with, so the
   * <code>&#64;WorkflowTask</code> method does not run a second time.
   *
   * @param deliveryLog The store to ask
   * @param deliveryKey The identity of the delivery
   * @param context The invocation context of the repeated delivery
   * @return The recorded outcome, empty where this delivery was not processed before
   */
  public Optional<WorkflowTaskOutcome> answerToARepeatedDelivery(
      final TaskDeliveryLog deliveryLog,
      final String deliveryKey,
      final TaskInvocationContext context) {

    final var recorded = deliveryLog
        .recordedDelivery(deliveryKey)
        .flatMap(delivery -> recordedOutcomeOf(deliveryLog, delivery, context));
    if (recorded.isEmpty()) {
      return recorded;
    }
    log.info(
        "Skipping the repeated delivery of task '{}' (BPMN process '{}' of workflow module "
            + "'{}', aggregate '{}'): it was processed before, reporting the recorded outcome "
            + "{} again",
        context.getTaskDefinition(),
        bpmnProcessId,
        workflowModuleId,
        context.getWorkflowAggregateId(),
        recorded.get().kind());
    metrics
        .taskRedeliveryDeduplicated(
            context.getAdapterId(),
            workflowModuleId,
            bpmnProcessId,
            context.getTaskDefinition());
    return recorded;

  }

  /**
   * Remembers a processed delivery within the transaction which also persists the
   * aggregate - the two commit together or not at all.
   *
   * @param deliveryLog The store to write to, <code>null</code> where there is none
   * @param deliveryKey The identity of the delivery
   * @param context The invocation context of the delivery
   * @param outcome What the handler produced
   */
  public void record(
      final TaskDeliveryLog deliveryLog,
      final String deliveryKey,
      final TaskInvocationContext context,
      final WorkflowTaskOutcome outcome) {

    if (deliveryLog == null) {
      return;
    }
    // the task travels with the record so the election of a later completion can read from
    // it which adapter holds that task, instead of asking every configured BPMS. The
    // element of the model and the BPMS' own id of the workflow travel with it for a
    // reader: they are what an extension and an operator address this task by outside
    // VanillaBP, and an adapter which names neither leaves both empty. The KIND of task is
    // what lets VanillaBP answer a caller who named the id of the other kind, which is a
    // mistake an application really makes
    final var recordWasWritten = deliveryLog.record(
        new TaskDelivery(deliveryKey, context.getAdapterId(), workflowModuleId, bpmnProcessId, context
            .getWorkflowAggregateId(), context
                .getWorkflowId(), context.getTaskDefinition(), context.getBpmnElementId(), context
                    .getTaskId(), outcome.kind().name(), outcome.errorCode(), outcome
                        .errorName(), Instant.now(), null, nameOf(context.getTaskKind())));
    if (!recordWasWritten) {
      reportHandlerRanTwiceAtTheSameTime(deliveryKey, context);
    }

  }

  /**
   * Writes down which workflow of the BPMS the aggregate of a started workflow belongs to, so
   * the BPMS election and an extension can read that id without asking a BPMS.
   *
   * <h4>Why the log and not a store of its own</h4>
   *
   * The row answers the same kind of question the row of a delivery answers - where is this
   * workflow - and it lives in the database of the workflow aggregate for the same reason. What
   * tells the two apart is {@link TaskDelivery#recordKind()}, and every question about open work
   * filters on it.
   *
   * <h4>When it is written, and when it is not</h4>
   *
   * At the earliest moment both values are known. For a start of the application that is phase
   * two, because a remote BPMS creates the instance after the caller's transaction committed, and
   * the row is written in the transaction of that dispatch. So a report of a changed aggregate
   * between the two finds no id, which is right: the instance does not exist yet either. For a
   * workflow the BPMS started on its own it is the transaction which persists the aggregate,
   * where the notification carries both values at once.
   * <p>
   * Nothing is written where no store serves this aggregate, or where the adapter names no
   * workflow - then everything stays as it was and the election probes.
   * <p>
   * The key is derived from the workflow module, the BPMN process and the aggregate
   * ({@link io.vanillabp.integration.spi.WorkflowStartKey}), so a dispatch which runs twice
   * writes nothing the second time instead of a second row.
   *
   * @param adapterId The ID of the adapter which started the workflow
   * @param workflowAggregateId The workflow aggregate of the started workflow
   * @param workflowId The BPMS' own id of the started workflow, or <code>null</code>
   */
  public void recordWorkflowStart(
      final String adapterId,
      final Object workflowAggregateId,
      final String workflowId) {

    if ((workflowAggregateId == null) || (workflowId == null) || workflowId.isBlank()) {
      return;
    }
    final var deliveryLog = resolveLog();
    if (deliveryLog == null) {
      return;
    }
    try {
      final var written = deliveryLog
          .recordWorkflowStart(
              TaskDelivery
                  .workflowStart(
                      adapterId,
                      workflowModuleId,
                      bpmnProcessId,
                      workflowAggregateId.toString(),
                      workflowId,
                      Instant.now()));
      log.debug(
          "Workflow '{}' of aggregate '{}' (BPMN process '{}' of workflow module '{}') {}",
          workflowId,
          workflowAggregateId,
          bpmnProcessId,
          workflowModuleId,
          written
              ? "was written down, so nobody has to ask a BPMS which of them holds it"
              : "was written down before - the row names this workflow already and nothing changed");
    } catch (final RuntimeException e) {
      // the workflow runs, and a row nobody could write costs the probing which happened
      // before this row existed. Failing the dispatch here would repeat a start which
      // succeeded
      log.warn(
          "The start of workflow '{}' of aggregate '{}' (BPMN process '{}' of workflow module "
              + "'{}') could not be written down - every operation on this workflow asks the "
              + "configured BPMS which of them holds it, as it did before the row existed",
          workflowId,
          workflowAggregateId,
          bpmnProcessId,
          workflowModuleId,
          e);
    }

  }

  /**
   * Says that the handler of this task ran twice at the same time, which is the one case
   * a record written after the work cannot prevent: a delivery starting while another
   * one is still running finds no record yet, so both run and only the one committing
   * first gets its record written.
   * <p>
   * The delivery which lost is the only place where the overlap becomes visible at all,
   * so it is where it is said out loud. Nothing is rolled back: the record which stands
   * describes work which was really done, and this delivery's own work committed just as
   * well.
   */
  private void reportHandlerRanTwiceAtTheSameTime(
      final String deliveryKey,
      final TaskInvocationContext context) {

    log.warn(
        """
            Two deliveries of task '{}' (BPMN process '{}' of workflow module '{}', workflow \
            aggregate '{}') were processed at the SAME time: adapter '{}' handed the task out \
            again while the first handler was still running, so both found no record and the \
            @WorkflowTask method ran twice. The record written by the delivery which committed \
            first stands and this one added nothing to it (delivery key '{}'). Whatever the \
            handler did beside writing the workflow aggregate, sending a mail for instance, \
            happened twice. Key such decisions on the state of the workflow aggregate, and where \
            the BPMS lets you say how long a task stays locked, a lock which outlasts the handler \
            keeps the second delivery from being handed out at all.""",
        context.getTaskDefinition(),
        bpmnProcessId,
        workflowModuleId,
        context.getWorkflowAggregateId(),
        context.getAdapterId(),
        deliveryKey);
    metrics
        .taskRedeliveryRanConcurrently(
            context.getAdapterId(),
            workflowModuleId,
            bpmnProcessId,
            context.getTaskDefinition());

  }

  /**
   * The outcome a recorded delivery is answered with. An outcome the core does not
   * know (a record written by a newer version, or a store returning something of its
   * own) yields an empty result: the handler runs again, which is the behaviour without
   * any log at all, and the WARN says why.
   */
  private Optional<WorkflowTaskOutcome> recordedOutcomeOf(
      final TaskDeliveryLog deliveryLog,
      final TaskDelivery delivery,
      final TaskInvocationContext context) {

    try {
      return Optional
          .of(
              switch (WorkflowTaskOutcome.Kind.valueOf(delivery.outcome())) {
                case COMPLETED -> WorkflowTaskOutcome.completed();
                case COMPLETION_PENDING -> stillOpen(deliveryLog, delivery, context);
                case BPMN_ERROR -> WorkflowTaskOutcome
                    .bpmnError(delivery.bpmnErrorCode(), delivery.bpmnErrorName());
              });
    } catch (final IllegalArgumentException e) {
      log.warn(
          "The recorded delivery '{}' of task '{}' (BPMN process '{}' of workflow module '{}') "
              + "reports the unknown outcome '{}' - processing the delivery again",
          delivery.deliveryKey(),
          context.getTaskDefinition(),
          bpmnProcessId,
          workflowModuleId,
          delivery.outcome());
      return Optional.empty();
    }

  }

  /**
   * How long a task left open by a <code>&#64;TaskId</code> handler has been waiting,
   * and whether that passed the maximum age configured for it
   * (<code>vanillabp.delivery.max-task-age</code>, resolvable per workflow module,
   * workflow and task).
   * <p>
   * This is the one place in VanillaBP which can answer the question at all. The record
   * was written when the handler ran and it is what answers every redelivery of that
   * task, so the distance between its timestamp and now IS the age of the open task -
   * no clock, no scheduler and no second bookkeeping are involved, and the granularity
   * follows whatever rhythm the BPMS redelivers in.
   * <p>
   * Reporting is one WARN per task, not one per redelivery: a task open for a year would
   * otherwise fill the log with the same line every time its lock is renewed. The memory
   * of what was already reported is bounded and lives in this collaborator - losing
   * an entry costs one repeated WARN, which is why it needs nothing durable.
   * <p>
   * This is also the one place which knows that a record is still in use, whichever BPMS
   * redelivered: the store is told so and keeps the record alive as long as
   * redeliveries keep coming, while the timestamp this age is measured from stays where
   * it is. The store collects the key rather than writing it here - the redelivery runs
   * in the transaction of the workflow aggregate, and an UPDATE per renewal of every open
   * task has no business in it.
   *
   * @param deliveryLog The store the record came from
   * @param delivery The record answering this delivery
   * @param context The invocation context of the repeated delivery
   * @return The outcome, carrying the age and whether it passed the maximum
   */
  private WorkflowTaskOutcome stillOpen(
      final TaskDeliveryLog deliveryLog,
      final TaskDelivery delivery,
      final TaskInvocationContext context) {

    deliveryLog.stillOpen(delivery.deliveryKey());

    if (delivery.recordedAt() == null) {
      // a store written before the timestamp was part of the record - the task is
      // open, its age is simply not known
      return WorkflowTaskOutcome.completionPending();
    }
    final var openFor = Duration.between(delivery.recordedAt(), Instant.now());
    final var maxTaskAge = properties == null
        ? DeliveryProperties.DEFAULT_MAX_TASK_AGE
        : properties.maxTaskAge(workflowModuleId, bpmnProcessId, context.getTaskDefinition());
    final var exceeded = !maxTaskAge.isZero() && (openFor.compareTo(maxTaskAge) > 0);
    // a workflow which was already running before this version was deployed was open
    // before VanillaBP ever wrote a record for it, so the record's timestamp is the
    // moment the task was first SEEN and not the moment it was created
    final var lowerBound = context.predatesDeployedVersion();
    if (exceeded && reportTaskAgeOnce(delivery.deliveryKey())) {
      log.warn(
          """
              Task '{}' of BPMN process '{}' of workflow module '{}' has been waiting for its \
              asynchronous completion for {}{}, which is longer than the {} configured by '{}' for it. \
              Workflow aggregate: '{}'. Either the application still owes this task a \
              'ProcessService#completeTask' respectively 'cancelTask', or nobody will ever send it \
              and the workflow waits forever. Raise the maximum age where such a wait is legitimate \
              (it may be set per workflow module, workflow and task), or set it to '0' to switch \
              this report off.{}""",
          context.getTaskDefinition(),
          bpmnProcessId,
          workflowModuleId,
          lowerBound
              ? "at least "
              : "",
          openFor,
          maxTaskAge,
          MigrationAdapterProperties.maxTaskAgeProperty(),
          context.getWorkflowAggregateId(),
          lowerBound
              ? " This workflow was already running before the version deployed now, so it was open"
                  + " before VanillaBP could write anything down about it: the age above counts from"
                  + " the first delivery this application saw, and the real one is larger."
              : "");
    }
    return WorkflowTaskOutcome.completionPending(openFor, exceeded);

  }

  /**
   * Whether the given task's age is reported now - <code>true</code> exactly once per
   * delivery key, which is once per task.
   *
   * @param deliveryKey The identity of the delivery answering this task
   * @return Whether to log the report
   */
  private boolean reportTaskAgeOnce(
      final String deliveryKey) {

    return reportedTaskAges.putIfAbsent(deliveryKey, Boolean.TRUE) == null;

  }

  /**
   * Whether an ended workflow of this BPMN process releases the records of its processed
   * task deliveries (<code>vanillabp.delivery.release-on-workflow-end</code>, overridable
   * per workflow module).
   *
   * @return Whether the records are released when a workflow ends
   */
  public boolean releasesOnWorkflowEnd() {

    return (properties != null) && properties.releasesDeliveryRecordsOnWorkflowEnd(workflowModuleId);

  }

  /**
   * Deletes the records of the processed task deliveries of ONE ended workflow. Invoked
   * within the transaction of the end notification (see
   * {@link io.vanillabp.integration.adapter.migration.workflowend.WorkflowEndedHandlers}),
   * so the deletion commits with it - and a notification whose transaction is rolled back
   * leaves the records where they were, to be released by the redelivered notification or
   * by the retention.
   *
   * @param workflowAggregateId The ID of the ended workflow's aggregate
   * @param recordedBefore Only records written before this moment are deleted - the bound
   *          which keeps the records of a SECOND workflow on the same aggregate
   * @return The number of records deleted
   */
  public int release(
      final String workflowAggregateId,
      final Instant recordedBefore) {

    final var deliveryLog = resolveLog();
    if (deliveryLog == null) {
      return 0;
    }
    final var released = deliveryLog
        .releaseRecordsOf(workflowModuleId, bpmnProcessId, workflowAggregateId, recordedBefore);
    log.debug(
        "Released {} task-delivery record(s) of the ended workflow '{}' (BPMN process '{}' of "
            + "workflow module '{}')",
        released,
        workflowAggregateId,
        bpmnProcessId,
        workflowModuleId);
    return released;

  }

  /**
   * Validates AT STARTUP that a store is available if any prioritized adapter may repeat a
   * delivery ({@link MigratableProcessService#deliversTasksAtLeastOnce()}) and
   * deduplication is switched on. Unlike the outbox this does NOT fail the boot: without a
   * store VanillaBP behaves exactly as it did before the feature existed (at-least-once,
   * the rule to key business decisions on the aggregate's state carries the case), so a
   * guiding warning naming both remedies is the honest answer - and it goes into the
   * block of the start instead of surfacing per delivery.
   * <p>
   * Nothing is resolved where no adapter can repeat a delivery: an application using an
   * embedded BPMS only must not be pushed towards a store it does not need.
   *
   * @param <A> The workflow-aggregate type the adapters' process services serve
   * @param adapterProcessServices The prioritized adapters of this BPMN process
   */
  public <A> void validateAtStartup(
      final List<MigratableProcessService<A>> adapterProcessServices) {

    validateReleaseAtStartup();

    final var atLeastOnceAdapters = adapterProcessServices
        .stream()
        .filter(MigratableProcessService::deliversTasksAtLeastOnce)
        .map(MigratableProcessService::getAdapterId)
        .filter(adapterId -> deduplicates(adapterId, null))
        .toList();
    if (atLeastOnceAdapters.isEmpty()) {
      return;
    }
    if (resolveLog() == null) {
      reportMissingLog(atLeastOnceAdapters.getFirst());
    }

  }

  /**
   * Validates AT STARTUP that the store resolved for this aggregate can do what
   * <code>release-on-workflow-end</code> promises. A store which does not implement
   * {@link TaskDeliveryLog#releaseRecordsOf(String, String, String, Instant)} keeps its
   * records until the retention passed - which is not wrong, but it is not what the
   * application configured, so it is said once at startup naming the store and the
   * property.
   * <p>
   * Nothing happens where the release is switched off: an application which did not ask
   * for it must not be told about a method its store does not have.
   */
  private void validateReleaseAtStartup() {

    if (!releasesOnWorkflowEnd()) {
      return;
    }
    final var deliveryLog = resolveLog();
    if (deliveryLog == null) {
      // no store at all means no records at all - there is nothing to release, and the
      // missing store is reported by the check for deduplication itself
      return;
    }
    final var storeClass = resolver != null
        ? resolver.storeClassOf(deliveryLog)
        : deliveryLog.getClass();
    if (implementsRelease(storeClass)) {
      return;
    }
    findings
        .warn(
            StartupTopic.CODE,
            "process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId),
            """
                The TaskDeliveryLog '%s' does not implement 'releaseRecordsOf', but '%s' is \
                switched on for this BPMN process - the records of an ended workflow are NOT \
                deleted when it ends but once '%s' passed. To solve this \
                either
                - implement io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf in \
                '%s', or
                - set '%s' to 'false' to state that the retention is what cleans up the \
                records."""
                .formatted(
                    storeClass.getName(),
                    MigrationAdapterProperties.releaseOnWorkflowEndProperty(workflowModuleId),
                    DeliveryProperties.RETENTION_PROPERTY,
                    storeClass.getName(),
                    MigrationAdapterProperties.releaseOnWorkflowEndProperty(workflowModuleId)));

  }

  /**
   * Whether the given store implements the release itself instead of inheriting the
   * default of {@link TaskDeliveryLog} which does nothing.
   *
   * @param storeClass The store's class, unwrapped by the platform integration
   * @return Whether the store releases records
   */
  private static boolean implementsRelease(
      final Class<?> storeClass) {

    try {
      // resolves to the override where there is one, and to the interface's default
      // method otherwise - which is exactly the question asked here
      final var method = storeClass
          .getMethod(
              "releaseRecordsOf",
              String.class,
              String.class,
              String.class,
              Instant.class);
      return method.getDeclaringClass() != TaskDeliveryLog.class;
    } catch (final NoSuchMethodException e) {
      // a store compiled against an older SPI: it cannot release either
      return false;
    }

  }

  /**
   * The adapter ids the records of still-open tasks name - asked of a store which was
   * resolved already, because an application which needs none must not be made to
   * materialize one for a question about it.
   *
   * @return The adapter ids, empty where nothing was resolved or the store cannot answer
   */
  public Set<String> adapterIdsOfOpenTasks() {

    return taskDeliveryLog == null
        ? Set.of()
        : taskDeliveryLog.adapterIdsOfOpenTasks(workflowModuleId, bpmnProcessId);

  }

  /**
   * Reports AT STARTUP that the BPMS holds tasks open which VanillaBP has no record of,
   * so their next delivery will run the <code>&#64;WorkflowTask</code> method again.
   *
   * <h4>What this catches</h4>
   *
   * The wiki promises that a repeated delivery does not run the handler a second time,
   * and that promise rests on a record written when the handler ran. A task which was
   * already open before this application first ran has none - which is exactly what an
   * upgrade from version 1 leaves behind, since version 1 kept no such records at all.
   * The window is real, it closes as those tasks are delivered once each, and without
   * this nobody can see it: no message is produced when it happens, because from the
   * core's point of view a delivery it has never seen is simply a new one.
   *
   * <h4>Why counting is all that is done</h4>
   *
   * Nothing can be adopted. A record would have to claim that the handler ran and left
   * the task open; a job which is activated and still there may equally be a handler
   * which crashed halfway, no BPMS can tell the two apart, and the wrong guess skips
   * business code which never ran. So the honest answer is the number plus the sentence
   * that the guards in the handlers still carry the case, which is what version 1 needed
   * anyway.
   *
   * <h4>When it stays silent</h4>
   *
   * Silent unless BOTH sides answer and both answers are interesting: a store which
   * cannot say whether it holds open records, a BPMS which cannot count its open tasks,
   * a store which DOES hold open records (an application which has been running), and a
   * BPMS holding nothing open (a fresh installation) each end it. So a normal restart
   * says nothing, a first start on an empty system says nothing, and the upgrade says
   * something once per BPMN process.
   *
   * @param <A> The workflow-aggregate type the adapters' process services serve
   * @param adapterProcessServices The prioritized adapters of this BPMN process
   */
  public <A> void reportOpenTasksNobodyRemembers(
      final List<MigratableProcessService<A>> adapterProcessServices) {

    final var deliveryLog = taskDeliveryLog;
    if (deliveryLog == null) {
      return;
    }
    final var hasRecords = deliveryLog.hasOpenRecords(workflowModuleId, bpmnProcessId);
    if ((hasRecords == null) || hasRecords.booleanValue()) {
      return;
    }
    adapterProcessServices
        .stream()
        .filter(MigratableProcessService::deliversTasksAtLeastOnce)
        .forEach(adapter -> {
          final var open = adapter.openTaskCount(workflowModuleId, bpmnProcessId);
          if ((open == null) || (open == 0)) {
            return;
          }
          findings
              .notice(
                  io.vanillabp.integration.spi.startup.StartupTopic.STORED_STATE,
                  "process '%s' of workflow module '%s', adapter '%s'"
                      .formatted(bpmnProcessId, workflowModuleId, adapter.getAdapterId()),
                  """
                      This adapter holds %d open task(s) of this BPMN process which VanillaBP has \
                      no record of. It remembers a delivery from the moment your handler ran, so \
                      tasks which were already open before this application first ran are not in \
                      that memory: the next delivery of each of them runs the @WorkflowTask method \
                      a SECOND time, which is what happens without the record and what VanillaBP 1 \
                      did for every delivery. Nothing can be repaired here - a record would have \
                      to claim your handler ran, and an activated job which is still there may \
                      just as well be a handler which crashed halfway. So keep the guards in your \
                      handlers until this number is zero, which it becomes as each of those tasks \
                      is delivered once."""
                      .formatted(open));
        });

  }

  /**
   * Answers from the record of this aggregate which adapter holds the task THIS CALL
   * names, so no BPMS has to be asked for it.
   * <p>
   * VanillaBP wrote that record while the handler of the task ran, in the database of the
   * workflow aggregate: it names the adapter which delivered the task, and it says whether
   * the application has closed that task since. Both answers are exactly what the walk over
   * the adapters would ask a BPMS for, one round trip per operation - on Camunda 8 the very
   * command the adapter's phase one sends again a moment later.
   * <p>
   * What decides whether the record can answer is the CALL rather than the operation: a call
   * which names a task asks about that task, however the operation is elected. Pushing a
   * changed aggregate into the scope of a task is the case where the two differ - the values
   * land in the workflow, and the task the call names is what says where. As long as that task
   * is open, the BPMS holding it is the BPMS holding the workflow around it, which is why the
   * answer stays one about a task (see decision 30 in the repository's DECISIONS.md). A task
   * which is over says nothing about the workflow around it, so a closed record answers only
   * the operations which end the task themselves.
   * <p>
   * The record is looked for under every BPMN process id this workflow service serves, this
   * instance's own id first. A task of a secondary process was delivered to the instance of
   * THAT process and its record therefore carries the secondary id, while the application
   * completes the task on the primary process service - so a read of the own id alone found
   * nothing for exactly the tasks a called process left open
   * ({@link #setBpmnProcessIdsToReadUnder(List)}).
   * <p>
   * <code>null</code> means the record cannot answer, and then everything happens as it did
   * before: no store, deliveries not deduplicated, the retention gone over the record, a BPMS
   * which reports no delivery identity, a workflow started before this version was deployed,
   * or an adapter which is not configured any more. That fallback is what keeps the record a
   * hint rather than a registry.
   *
   * @param <A> The workflow-aggregate type the adapters' process services serve
   * @param operation The operation being elected for
   * @param workflowAggregateId The workflow aggregate the operation is about
   * @param args The operation's arguments, which name the task
   * @param adapterProcessServices The prioritized adapters of this BPMN process
   * @return The location, or <code>null</code> where the record cannot answer
   */
  public <A> WorkflowLocator.Location<A> locate(
      final PhaseOperation operation,
      final Object workflowAggregateId,
      final Map<String, String> args,
      final List<MigratableProcessService<A>> adapterProcessServices) {

    if (workflowAggregateId == null) {
      return null;
    }
    final var taskId = theTaskNamedBy(args);
    if (taskId == null) {
      return null;
    }
    final var deliveryLog = resolveLog();
    if (deliveryLog == null) {
      return null;
    }
    final var record = recordOfTaskUnderAnyServedId(deliveryLog, workflowAggregateId.toString(), taskId);
    if ((record == null) || (record.adapterId() == null)) {
      return null;
    }
    if (namesAnotherKindOfTask(record, operation)) {
      // the caller named a task of one kind and asks for a command of the other - the id of
      // a user task handed to a job command, say. Electing from the record would send that
      // command to the BPMS which really holds the task, and the BPMS would answer "no such
      // job" at dispatch time, long after the caller was gone. So the record does not answer
      // this election: the adapters are asked, every one of them says it knows no such task,
      // and the failure says which kind the id is (see
      // MigrationProcessService#unknownToEveryBpms)
      log.debug(
          "Task '{}' of aggregate '{}' was delivered as {} while {} asks a BPMS about {} - "
              + "probing instead",
          taskId,
          workflowAggregateId,
          record.taskKind(),
          operation.name(),
          kindAskedAboutBy(operation));
      return null;
    }
    final var adapter = adapterProcessServices
        .stream()
        .filter(candidate -> candidate.getAdapterId().equals(record.adapterId()))
        .findFirst()
        .orElse(null);
    if (adapter == null) {
      // the adapter which delivered the task is not prioritized for this workflow any more -
      // the walk elects from the configuration as it reads now
      log.debug(
          "Adapter '{}' recorded for task '{}' of aggregate '{}' is not a prioritized adapter - "
              + "probing instead",
          record.adapterId(),
          taskId,
          workflowAggregateId);
      return null;
    }
    final var taskIsOpen = record.taskClosedAt() == null;
    if (!taskIsOpen && !endsTheTaskItNames(operation)) {
      // a task which is over is no statement about the workflow around it: the scope the
      // push writes into outlives the task, and so may the workflow. Only an operation
      // which ends the task itself may read a closed record as "there is nothing left to
      // do here"
      log.debug(
          "Task '{}' of aggregate '{}' was closed at {}, which does not say whether its workflow "
              + "still runs - probing instead",
          taskId,
          workflowAggregateId,
          record.taskClosedAt());
      return null;
    }
    metrics
        .taskElectionAnsweredFromRecord(record.adapterId(), workflowModuleId, bpmnProcessId, operation.name());
    log.debug(
        "Adapter '{}' delivered task '{}' of aggregate '{}' and the task is {} - no BPMS is asked "
            + "which of them holds it",
        record.adapterId(),
        taskId,
        workflowAggregateId,
        taskIsOpen
            ? "still open"
            : "closed since %s".formatted(record.taskClosedAt()));
    return new WorkflowLocator.Location<>(
        taskIsOpen
            ? WorkflowAwareness.ACTIVE
            : WorkflowAwareness.COMPLETED, adapter, null);

  }

  /**
   * Writes into the record that the application's completion or cancellation of a task
   * reached the BPMS - here and not when the caller asked, because until this moment the
   * task is still open and its redeliveries still renew the lock the BPMS holds on it.
   * <p>
   * A failure is reported and swallowed: the operation itself went through, the outbox entry
   * is done, and repeating a dispatch which succeeded because a mark did not is the worse of
   * the two. What is lost is one BPMS round trip on the next operation naming that task.
   * <p>
   * Asked of the OPERATION and not of the call, unlike the election in
   * {@link #locate(PhaseOperation, Object, Map, List)}: a call which merely names a task
   * leaves that task open - pushing a changed aggregate into its scope completes nothing -
   * so writing the moment of a completion there would close a record while the BPMS still
   * hands the task out.
   * <p>
   * The row is looked for under every BPMN process id this workflow service serves, the own
   * id first, and the first one marked ends the search: the delivery which opened that row
   * may have been one of a secondary process, and then the row does not carry the id the
   * application called.
   *
   * @param operation The operation which was dispatched
   * @param workflowAggregateId The workflow aggregate it was about
   * @param args The operation's arguments, which name the task
   * @param subject The workflow the operation was about, as the message names it
   */
  public void writeDownThatTheTaskIsClosed(
      final PhaseOperation operation,
      final Object workflowAggregateId,
      final Map<String, String> args,
      final String subject) {

    if (!endsTheTaskItNames(operation) || (workflowAggregateId == null)) {
      return;
    }
    final var taskId = theTaskNamedBy(args);
    if (taskId == null) {
      return;
    }
    final var deliveryLog = resolveLog();
    if (deliveryLog == null) {
      return;
    }
    try {
      markTaskClosedWhereTheRecordSits(deliveryLog, workflowAggregateId.toString(), taskId);
    } catch (final RuntimeException e) {
      log.warn(
          "Task '{}' of {} was closed in its BPMS, but the delivery record could not be marked "
              + "accordingly - the next operation naming this task asks the configured BPMS again "
              + "instead of being answered from the record",
          taskId,
          subject,
          e);
    }

  }

  /**
   * Writes into the record that the BPMS itself ended the task - the other half of
   * {@link #writeDownThatTheTaskIsClosed(PhaseOperation, Object, Map, String)}, for the
   * tasks nobody in the application asked to close.
   * <p>
   * Called on the DELIVERY path, inside the transaction of the delivery, and the mark is
   * safe there for the reason it is not safe when a caller merely asks: a task the BPMS
   * cancelled is never handed out again, so there is no lock left for a redelivery to
   * renew. Without it the record of that task stays open until the retention deletes it,
   * and everything which reads the open work of a workflow reads a task which is gone.
   * <p>
   * A failure is not swallowed. The mark and the record of this delivery are written in one
   * transaction, and a delivery which cannot close the task it just reported cancelled would
   * leave the store saying two different things about that task.
   *
   * @param deliveryLog The store to write to, <code>null</code> where there is none
   * @param context The invocation context of the cancelling delivery
   */
  public void writeDownThatTheBpmsEndedTheTask(
      final TaskDeliveryLog deliveryLog,
      final TaskInvocationContext context) {

    if ((deliveryLog == null) || (context.getTaskId() == null) || (context.getWorkflowAggregateId() == null)) {
      return;
    }
    markTaskClosedWhereTheRecordSits(deliveryLog, context.getWorkflowAggregateId(), context.getTaskId());

  }

  /**
   * Whether the operation ENDS the task it names, which is what an operation elected by
   * whoever holds a task does: completing it or cancelling it. An operation elected by
   * whoever holds the WORKFLOW may name a task as well, and then it says where its values
   * go rather than that the task is finished.
   */
  private static boolean endsTheTaskItNames(
      final PhaseOperation operation) {

    return (operation.election() == Election.HOLDS_THE_TASK) || (operation.election() == Election.HOLDS_THE_USER_TASK);

  }

  /**
   * Which kind of task an operation asks a BPMS about, which is the election it is elected
   * by: a task and a user task are two questions because their ids live in namespaces of
   * their own.
   *
   * @param operation The operation being elected for
   * @return The kind, or <code>null</code> where the operation asks about no task at all -
   *         which is every operation about the workflow, however it names a task
   */
  public static TaskKind kindAskedAboutBy(
      final PhaseOperation operation) {

    return switch (operation.election()) {
      case HOLDS_THE_TASK -> TaskKind.TASK;
      case HOLDS_THE_USER_TASK -> TaskKind.USER_TASK;
      default -> null;
    };

  }

  /**
   * Whether the record says that the id belongs to the OTHER kind of task than the one this
   * operation asks a BPMS about.
   * <p>
   * Both sides have to say something. A record written by an adapter which does not report
   * the kind names none, and an operation about the workflow asks about no kind, so neither
   * contradicts anything and everything stays as it was.
   *
   * @param record The record of the task the call names
   * @param operation The operation being elected for
   * @return Whether the two name different kinds
   */
  private static boolean namesAnotherKindOfTask(
      final TaskDelivery record,
      final PhaseOperation operation) {

    final var asked = kindAskedAboutBy(operation);
    final var recorded = kindNamedBy(record.taskKind());
    return (asked != null) && (recorded != null) && (recorded != asked);

  }

  /**
   * The name a store keeps a kind of task under, which is the enum's own name - the way the
   * outcome of a delivery travels into a record as well.
   *
   * @param kind The kind the delivering adapter reported, or <code>null</code>
   * @return The name, or <code>null</code> where the adapter reported none
   */
  private static String nameOf(
      final TaskKind kind) {

    return kind == null
        ? null
        : kind.name();

  }

  /**
   * The kind a record names, or <code>null</code> where it names nothing this version knows.
   * A record written by a NEWER version of VanillaBP may hold a kind which did not exist
   * yet, and a kind nobody here understands says nothing - the alternative would be to
   * read it as a contradiction and send a caller to a method of a kind nobody knows.
   *
   * @param recordedKind The text the record holds, which may be <code>null</code>
   * @return The kind, or <code>null</code>
   */
  private static TaskKind kindNamedBy(
      final String recordedKind) {

    if (recordedKind == null) {
      return null;
    }
    for (final var kind : TaskKind.values()) {
      if (kind.name().equals(recordedKind)) {
        return kind;
      }
    }
    return null;

  }

  /**
   * Which kind of task the id a call names was delivered as, read from the record of that
   * task - what the failure of an operation no BPMS could serve says instead of listing
   * what the id could be.
   * <p>
   * It answers <code>null</code> where nothing is known, and that is the ordinary case: an
   * application without a store, an adapter which does not report the kind, a record the
   * retention deleted, or a task which was never delivered under this id at all. A failure
   * of the store is swallowed here, unlike everywhere else: a message is being built for a
   * failure the caller already has, and replacing it with a second one would hide the first.
   *
   * @param workflowAggregateId The workflow aggregate the operation is about
   * @param args The operation's arguments, which name the task
   * @return The kind the record names, or <code>null</code>
   */
  public TaskKind recordedKindOfTheTaskNamedBy(
      final Object workflowAggregateId,
      final Map<String, String> args) {

    if (workflowAggregateId == null) {
      return null;
    }
    final var taskId = theTaskNamedBy(args);
    if (taskId == null) {
      return null;
    }
    final var deliveryLog = resolveLog();
    if (deliveryLog == null) {
      return null;
    }
    try {
      final var record = recordOfTaskUnderAnyServedId(deliveryLog, workflowAggregateId.toString(), taskId);
      return record == null
          ? null
          : kindNamedBy(record.taskKind());
    } catch (final RuntimeException e) {
      log
          .debug(
              "Could not read which kind of task '{}' of aggregate '{}' (BPMN process '{}' of "
                  + "workflow module '{}') was delivered as",
              taskId,
              workflowAggregateId,
              bpmnProcessId,
              workflowModuleId,
              e);
      return null;
    }

  }

  /**
   * How long a record is kept, which is what a caller has to know who got no sharper answer:
   * a task completed longer ago than this has no record left to read the kind from.
   *
   * @return The retention, or <code>null</code> where no configuration was bound (tests)
   */
  public Duration retentionOfTheRecords() {

    return properties == null
        ? null
        : properties.resolvedDeliveryRetention();

  }

  /**
   * The BPMS' own id of the workflow of the given aggregate, as far as a record knows one.
   * <p>
   * Two reads, in this order, under every BPMN process id this workflow service serves. The row
   * about the START of that workflow comes first: it is a lookup by key, it exists from the
   * moment the workflow was created, and it holds the id of the workflow this aggregate IS. The
   * OPEN records of the aggregate are the second read, which answers for a workflow started
   * before the start row existed and for one whose start row the retention took. Either way a
   * workflow VanillaBP heard about is named here after a restart, which the election cache
   * cannot promise. The first row naming a workflow answers - the rows of one aggregate and one
   * BPMN process belong to one workflow.
   * <p>
   * A hint and never an answer. It says what was true when the delivery ran, not whether the
   * workflow still runs, and an adapter which reports no workflow id leaves nothing here.
   *
   * @param workflowAggregateId The workflow aggregate the caller is asking about
   * @return The workflow's id in the BPMS, or <code>null</code> where no record knows one
   */
  public String workflowIdOf(
      final Object workflowAggregateId) {

    if (workflowAggregateId == null) {
      return null;
    }
    final var deliveryLog = resolveLog();
    if (deliveryLog == null) {
      return null;
    }
    try {
      for (final var candidate : bpmnProcessIdsToReadUnder) {
        final var started = deliveryLog
            .workflowStartOf(workflowModuleId, candidate, workflowAggregateId.toString())
            .map(TaskDelivery::workflowId)
            .filter(java.util.Objects::nonNull)
            .orElse(null);
        if (started != null) {
          return started;
        }
      }
      for (final var candidate : bpmnProcessIdsToReadUnder) {
        final var known = deliveryLog
            .openTasksOfAggregate(workflowModuleId, candidate, workflowAggregateId.toString())
            .stream()
            .map(TaskDelivery::workflowId)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
        if (known != null) {
          return known;
        }
      }
    } catch (final RuntimeException e) {
      // a hint nobody can read is a hint nobody has: the election runs either way, one
      // round trip slower
      log
          .debug(
              "Could not read which workflow of the BPMS aggregate '{}' (BPMN process '{}' of "
                  + "workflow module '{}') belongs to",
              workflowAggregateId,
              bpmnProcessId,
              workflowModuleId,
              e);
    }
    return null;

  }

  /**
   * The record of the given task, looked for under every BPMN process id this workflow
   * service serves, the own id first. The first one found wins: a task id belongs to one
   * activation of one process, so there is nothing to choose between.
   *
   * @param deliveryLog The store to ask
   * @param workflowAggregateId The workflow aggregate in serialized form
   * @param taskId The BPMS' identity of the task
   * @return The record or <code>null</code> where no id holds one
   */
  private TaskDelivery recordOfTaskUnderAnyServedId(
      final TaskDeliveryLog deliveryLog,
      final String workflowAggregateId,
      final String taskId) {

    for (final var candidate : bpmnProcessIdsToReadUnder) {
      final var record = deliveryLog
          .recordOfTask(workflowModuleId, candidate, workflowAggregateId, taskId)
          .orElse(null);
      if (record != null) {
        return record;
      }
    }
    return null;

  }

  /**
   * Marks the record of the given task closed, under whichever BPMN process id holds it.
   * Stops at the first id whose record was marked, so a service serving one process pays
   * exactly the one update it always paid.
   *
   * @param deliveryLog The store to write to
   * @param workflowAggregateId The workflow aggregate in serialized form
   * @param taskId The BPMS' identity of the closed task
   */
  private void markTaskClosedWhereTheRecordSits(
      final TaskDeliveryLog deliveryLog,
      final String workflowAggregateId,
      final String taskId) {

    for (final var candidate : bpmnProcessIdsToReadUnder) {
      if (deliveryLog.markTaskClosed(workflowModuleId, candidate, workflowAggregateId, taskId) > 0) {
        return;
      }
    }

  }

  /**
   * The task a call names, or <code>null</code> where it names none - the question which
   * decides whether the record of a task can be asked at all.
   */
  private static String theTaskNamedBy(
      final Map<String, String> args) {

    final var taskId = args.get(PhaseTwoCall.ARG_TASK_ID);
    return ((taskId == null) || taskId.isBlank())
        ? null
        : taskId;

  }

}
