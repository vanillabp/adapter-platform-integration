package io.vanillabp.integration.adapter.migration.workflowstart;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * The <code>&#64;WorkflowStartedByBpms</code> methods of the application, per workflow
 * module and BPMN process, and the start events which the adapters reported for those
 * processes. This class does the work behind the adapter-facing
 * {@link io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker}. The
 * workflow-task registry implements that interface and hands every call on to here.
 */
public class BpmsInitiatedStarts {

  private static final Logger log = LoggerFactory.getLogger(BpmsInitiatedStarts.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  private static class RegistryEntry {

    private final List<BpmsInitiatedStartHandler> handlers = new LinkedList<>();

    /**
     * The start events which the adapters that deploy this process reported. Several
     * adapters may report the same process (the migration case). The application may serve
     * all of them together.
     */
    private final List<BpmsInitiatedStartSpec> startEvents = new LinkedList<>();

  }

  private final Map<RegistryKey, RegistryEntry> entries = new ConcurrentHashMap<>();

  /**
   * What the BPMS know about the deployed versions of the BPMN processes. The
   * {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}
   * owns it and shares it, because all three annotations have a <code>version</code>
   * attribute.
   */
  private final io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions processVersions;

  /**
   * Which ids the application declares without a deployment. The registry owns this as
   * well. For such an id every version which a BPMS holds is an older one. The version
   * exemption below has to read it the same way as its twin in the registry
   * ({@code WorkflowTaskRegistry#servesOnlyOlderVersions}).
   */
  private final io.vanillabp.integration.adapter.migration.workflowtask.DeclaredBpmnProcesses declaredProcesses;

  /**
   * Built by the workflow-task registry, once per application. Both arguments belong to
   * that registry. They are read and not copied, because a
   * <code>&#64;WorkflowStartedByBpms</code> method has to get the same verdict about a
   * version as a <code>&#64;WorkflowTask</code> method gets from the registry.
   *
   * @param processVersions What the BPMS know about the deployed versions of the BPMN
   *          processes
   * @param declaredProcesses Which BPMN process ids the application declares without any
   *          adapter deploying them
   */
  public BpmsInitiatedStarts(
      final io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions processVersions,
      final io.vanillabp.integration.adapter.migration.workflowtask.DeclaredBpmnProcesses declaredProcesses) {

    this.processVersions = processVersions;
    this.declaredProcesses = declaredProcesses;

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

    final var handlers = BpmsInitiatedStartScanner
        .scan(workflowServiceClass, workflowAggregateClass, workflowServiceBean, inherited);
    if (handlers.isEmpty()) {
      return;
    }
    final var entry = entries
        .computeIfAbsent(new RegistryKey(workflowModuleId, bpmnProcessId), key -> new RegistryEntry());
    synchronized (entry) {
      // one by one, so that two methods of the SAME class which serve one start event are
      // compared with each other as well
      handlers
          .forEach(handler -> {
            failOnDuplicateWiring(
                workflowModuleId,
                bpmnProcessId,
                entry,
                handler,
                io.vanillabp.integration.adapter.migration.workflowtask.VersionRange.NO_RESOLVER);
            entry.handlers.add(handler);
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

    entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .forEach(entry -> {
          final var bpmnProcessId = entry.getKey().bpmnProcessId();
          final var registryEntry = entry.getValue();
          if (registryEntry.handlers.stream().allMatch(handler -> handler.versionTags().isEmpty())) {
            return;
          }
          processVersions.warmUp(workflowModuleId, bpmnProcessId);
          final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
          synchronized (registryEntry) {
            registryEntry.handlers
                .forEach(handler -> handler
                    .versionTags()
                    .forEach(tag -> processVersions.reportUnknownVersionTag(
                        workflowModuleId,
                        bpmnProcessId,
                        tag,
                        "method '%s'%s"
                            .formatted(handler.describe(), handler.describeVersionOrigin()))));
            registryEntry.handlers
                .forEach(handler -> failOnDuplicateWiring(
                    workflowModuleId,
                    bpmnProcessId,
                    registryEntry,
                    handler,
                    resolver));
          }
        });

  }

  private static void failOnDuplicateWiring(
      final String workflowModuleId,
      final String bpmnProcessId,
      final RegistryEntry entry,
      final BpmsInitiatedStartHandler handler,
      final io.vanillabp.integration.adapter.migration.workflowtask.VersionRange.ProcessVersionResolver resolver) {

    final var duplicate = entry.handlers
        .stream()
        .filter(existing -> existing != handler)
        .filter(existing -> java.util.Objects.equals(existing.getStartEventId(), handler.getStartEventId()))
        // overlapping version ranges are ambiguous. Ranges which do not overlap are a
        // valid way to serve several process versions
        .filter(existing -> existing.overlapsVersions(handler, resolver))
        .findFirst();
    if (duplicate.isPresent()) {
      throw new IllegalStateException(
          """
              The @WorkflowStartedByBpms methods '%s' (version %s) and '%s' (version %s) both serve \
              %s of BPMN process '%s' of workflow module '%s'! Remove one of them, name the start \
              events they serve by @WorkflowStartedByBpms(id = ...) or distinguish them by version - \
              on the method by @WorkflowStartedByBpms(version = ...), or, where a whole class serves \
              one generation of the model, on the class by @BpmnProcess(version = ...)."""
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
   * The @WorkflowStartedByBpms methods registered for that BPMN process. Each comes with the
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
  public java.util.List<io.vanillabp.integration.adapter.migration.workflowtask.HandlerVersions> handlerVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.function.Predicate<io.vanillabp.integration.adapter.migration.workflowtask.ServedVersions> serves) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      return java.util.List.of();
    }
    return entry.handlers
        .stream()
        .map(handler -> new io.vanillabp.integration.adapter.migration.workflowtask.HandlerVersions(
            handler.describe(), "@WorkflowStartedByBpms method '%s' (version %s)"
                .formatted(handler.describe(),
                    handler.describeVersionsWithOrigin()), serves.test(handler.servedVersions())))
        .toList();

  }

  /**
   * Registers the start events which an adapter reported while it wired a deployed BPMN
   * process, and checks the application's methods against them.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param startEvents The start events of the process
   * @param workflowAggregateClass The workflow aggregate of that process, written into the
   *          method which the message suggests. May be <code>null</code>
   * @throws IllegalStateException If a start event which the BPMS fires has no method, if a
   *           method serves a process without such a start event, or if a method names a
   *           start event which the process does not have
   */
  public void validate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmsInitiatedStartSpec> startEvents,
      final Class<?> workflowAggregateClass) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      // the process has no @WorkflowStartedByBpms method at all. Where the BPMS can start
      // it, that ends the boot. Nobody would build the aggregate when the timer fires, and
      // three in the morning is a bad moment to find out
      refuseStartEventsWithoutAMethod(
          workflowModuleId, bpmnProcessId, startEvents, List.of(), workflowAggregateClass);
      return;
    }
    synchronized (entry) {
      startEvents
          .stream()
          .filter(spec -> entry.startEvents
              .stream()
              .noneMatch(known -> known.elementId().equals(spec.elementId())))
          .forEach(entry.startEvents::add);

      refuseStartEventsWithoutAMethod(
          workflowModuleId, bpmnProcessId, entry.startEvents, entry.handlers, workflowAggregateClass);

      if (entry.startEvents.isEmpty()) {
        throw new IllegalStateException(
            """
                The @WorkflowStartedByBpms method(s) %s serve BPMN process '%s' of workflow module \
                '%s', but no adapter reported a start event of that process! Either the method \
                belongs to another process, or the model holds its start events elsewhere - a start \
                event of an event subprocess fires inside a workflow which already runs and starts \
                none."""
                .formatted(
                    describeHandlers(entry.handlers),
                    bpmnProcessId,
                    workflowModuleId));
      }

      entry.handlers
          .stream()
          .filter(handler -> handler.getStartEventId() != null)
          // A method kept for an OLDER version names a start event which the deployed
          // model may not have any more. That is what it is for. The startup check answers
          // whether such a version still exists. It reports a method which serves no held
          // version as dead.
          .filter(handler -> !servesOnlyOlderVersions(workflowModuleId, bpmnProcessId, handler))
          .filter(handler -> entry.startEvents
              .stream()
              .noneMatch(spec -> spec.elementId().equals(handler.getStartEventId())))
          .findFirst()
          .ifPresent(handler -> {
            throw new IllegalStateException(
                """
                    The @WorkflowStartedByBpms method '%s' serves start event '%s' of BPMN process '%s' \
                    of workflow module '%s', but that process has no such start event! Its start \
                    events are: %s."""
                    .formatted(
                        handler.describe(),
                        handler.getStartEventId(),
                        bpmnProcessId,
                        workflowModuleId,
                        describeStartEvents(entry.startEvents)));
          });
    }

  }

  /**
   * Judges the <code>&#64;WorkflowStartedByBpms</code> methods of the BPMN process ids
   * which the workflow module DECLARES without deploying a model for them. It judges them
   * against the start events of the versions which the BPMS still holds under such an id.
   * <p>
   * Nothing wires such an id during a boot. The id which a renamed BPMN process left behind
   * arrives without a model. So {@link #validate} is never called for it, and nothing
   * judges its methods. Meanwhile the BPMS may fire the timer of the old model every day.
   * The judgement needs the answer of
   * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog#startEventsOfVersion},
   * and this is where it is used.
   * <p>
   * Every finding is a warning which names the versions it comes from. It never ends a
   * boot. The models read here are models which nobody can change any more. A check which
   * reads them says what it found instead of refusing (see decision 38 in the repository's
   * DECISIONS.md). Where a version cannot be read at all, this stays silent for that BPMS.
   * A start event might be in exactly the model which could not be read.
   *
   * @param workflowModuleId The workflow module which finished deploying
   */
  public void validateAgainstVersionsTheBpmsHolds(
      final String workflowModuleId) {

    if (declaredProcesses == null) {
      return;
    }
    entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .filter(entry -> declaredProcesses
            .isDeclaredWithoutDeployment(workflowModuleId, entry.getKey().bpmnProcessId()))
        .forEach(entry -> validateAgainstVersionsTheBpmsHolds(
            workflowModuleId,
            entry.getKey().bpmnProcessId(),
            entry.getValue()));

  }

  /**
   * The same for one BPMN process which is only declared, once for each BPMS which answers
   * for it. The versions belong to one BPMS, so the message names the adapter which holds
   * them.
   */
  private void validateAgainstVersionsTheBpmsHolds(
      final String workflowModuleId,
      final String bpmnProcessId,
      final RegistryEntry entry) {

    processVersions
        .registeredCatalogs(workflowModuleId, bpmnProcessId)
        .forEach(registered -> {
          final var heldVersions = registered
              .catalog()
              .deployedVersionsOf(workflowModuleId, bpmnProcessId);
          if ((heldVersions == null) || heldVersions.isEmpty()) {
            // the startup check for old process versions reports an id under which nothing
            // is held, in its own words and once per adapter
            return;
          }
          final var versionsRead = new LinkedList<String>();
          final var startEvents = new LinkedList<BpmsInitiatedStartSpec>();
          for (final var held : heldVersions) {
            final var version = held.version();
            if (version == null) {
              continue;
            }
            final var eventsOfVersion = registered
                .catalog()
                .startEventsOfVersion(workflowModuleId, bpmnProcessId, version);
            if (eventsOfVersion == null) {
              // one model which cannot be read makes every verdict about this id a guess.
              // So nothing is said about this BPMS at all
              return;
            }
            versionsRead.add(version);
            eventsOfVersion
                .stream()
                .filter(spec -> startEvents
                    .stream()
                    .noneMatch(known -> known.elementId().equals(spec.elementId())))
                .forEach(startEvents::add);
          }
          if (versionsRead.isEmpty()) {
            return;
          }
          reportStartEventsTheHeldVersionsLack(
              workflowModuleId, bpmnProcessId, registered.adapterId(), entry, versionsRead, startEvents);
        });

  }

  /**
   * Says what the methods of an id which is only declared serve, and the versions held by
   * the BPMS do not have. That is the whole process where no held version starts on its
   * own, and the single method otherwise.
   */
  private void reportStartEventsTheHeldVersionsLack(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final RegistryEntry entry,
      final List<String> versionsRead,
      final List<BpmsInitiatedStartSpec> startEvents) {

    synchronized (entry) {
      if (startEvents.isEmpty()) {
        log
            .warn(
                """
                    The @WorkflowStartedByBpms method(s) {} serve BPMN process '{}' of workflow module \
                    '{}', which this application declares without deploying a model for it, but none of \
                    the version(s) adapter '{}' still holds under that id ({}) has a start event the \
                    BPMS fires on its own - those methods never run. \
                    Either the declared id is misspelled, and this workflow module deploys {}, or the \
                    methods belong to another process: a workflow started by the application gets its \
                    aggregate from ProcessService#startWorkflow.""",
                describeHandlers(entry.handlers),
                bpmnProcessId,
                workflowModuleId,
                adapterId,
                String.join(", ", versionsRead),
                deployedProcessIdsOf(workflowModuleId));
        return;
      }
      entry.handlers
          .stream()
          .filter(handler -> handler.getStartEventId() != null)
          .filter(handler -> startEvents
              .stream()
              .noneMatch(spec -> spec.elementId().equals(handler.getStartEventId())))
          .forEach(handler -> log
              .warn(
                  """
                      The @WorkflowStartedByBpms method '{}' serves start event '{}' of BPMN process '{}' \
                      of workflow module '{}', which this application declares without deploying a model \
                      for it, but no version adapter '{}' still holds under that id has such a start event \
                      fired by the BPMS - that method never runs. The start events of the \
                      version(s) {} are: {}. Correct the id against the model the BPMS holds, or remove \
                      the method once the workflows it was kept for have ended.""",
                  handler.describe(),
                  handler.getStartEventId(),
                  bpmnProcessId,
                  workflowModuleId,
                  adapterId,
                  String.join(", ", versionsRead),
                  describeStartEvents(startEvents)));
    }

  }

  /**
   * The BPMN process ids of that workflow module under which a model WAS deployed during
   * this boot. A developer compares a declared id which reaches nothing against them.
   */
  private String deployedProcessIdsOf(
      final String workflowModuleId) {

    final var deployed = declaredProcesses
        .deployedProcessesOf(workflowModuleId)
        .stream()
        .map("'%s'"::formatted)
        .collect(Collectors.joining(", "));
    return deployed.isEmpty()
        ? "none"
        : deployed;

  }

  /**
   * Whether the method exists for versions OLDER than the one this boot deployed. Then it
   * names an element of a model which is not the deployed one.
   * <p>
   * A BPMN process id which the application declares WITHOUT bringing a model is the same
   * case with a different limit. An example is the id which a renamed process left behind.
   * This boot deployed no version of it at all. So a method which serves the id is kept for
   * every version which the BPMS holds. The twin of this method in the registry
   * ({@code WorkflowTaskRegistry#servesOnlyOlderVersions}) got this branch first. Without it
   * here, a method which matches a held version of such an id would be checked against the
   * start events of a model which does not exist.
   */
  private boolean servesOnlyOlderVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmsInitiatedStartHandler handler) {

    final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
    if ((declaredProcesses != null) && declaredProcesses.isDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId)) {
      final var heldVersions = processVersions
          .registeredCatalogs(workflowModuleId, bpmnProcessId)
          .stream()
          .map(registered -> registered
              .catalog()
              .deployedVersionsOf(workflowModuleId, bpmnProcessId))
          .filter(java.util.Objects::nonNull)
          .flatMap(List::stream)
          .map(io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion::version)
          .filter(java.util.Objects::nonNull)
          .distinct()
          .toList();
      if (!heldVersions.isEmpty()) {
        return heldVersions
            .stream()
            .anyMatch(version -> handler.matchesVersion(version, resolver));
      }
      // nothing held and nothing to ask. Unlike the twin in the registry, this check may run
      // BEFORE the task wiring has settled which ids are declared and which are deployed. An
      // id which looks declared-only and has no catalog is answered by the strict path
      // below. So a deployed process which is checked first is judged as strictly as
      // before
    }
    final var deployedVersions = processVersions
        .registeredCatalogs(workflowModuleId, bpmnProcessId)
        .stream()
        .map(registered -> processVersions.deployedVersion(registered.adapterId(), workflowModuleId, bpmnProcessId))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (deployedVersions.isEmpty()) {
      return false;
    }
    return deployedVersions
        .stream()
        .noneMatch(version -> handler.matchesVersion(version, resolver));

  }

  /**
   * Builds the workflow aggregate of a workflow the BPMS started on its own.
   *
   * @param <A> The workflow-aggregate type
   * @param processService The process service of the BPMN process
   * @param context The adapter's notification
   * @param transactionRunner The platform's transaction runner
   * @return The aggregate's ID and the variables the adapter writes back
   */
  public <A> BpmsInitiatedStartResult start(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context,
      final TransactionRunner transactionRunner) {

    final var entry = entries
        .get(
            new RegistryKey(processService.getWorkflowModuleId(), processService.getBpmnProcessId()));
    final var wired = entry == null
        ? List.<BpmsInitiatedStartHandler>of()
        : entry.handlers
            .stream()
            .filter(candidate -> candidate.matchesStartEvent(context.getStartEventId()))
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
    // a workflow which the BPMS already names is ours and needs no method. So the complaint
    // is only written down here, and thrown where the start turns out to be a foreign one
    final var refusalWithoutAMethod = handler != null
        ? null
        : startNobodyBuilds(processService, context, wired);

    final var result = BpmsInitiatedStartExecution
        .run(processService, handler, refusalWithoutAMethod, context, transactionRunner);
    // the BPMS just built this workflow through us, so it holds it
    if (result != null) {
      processService.rememberWorkflowAdapter(result.workflowAggregateId(), context.getAdapterId());
    }
    return result;

  }

  /**
   * Whether the BPMS fires this kind of start event by itself. Then such a start is certain
   * to arrive without a name. A none or message start event has the shape of the
   * application's own start. So a process with only those needs no
   * <code>&#64;WorkflowStartedByBpms</code> method until somebody starts it without
   * VanillaBP. That start says so when it happens.
   *
   * @param kind The kind of start event an adapter reported
   * @return Whether the BPMS fires it without anybody asking
   */
  private static boolean firedWithoutTheApplication(
      final BpmsStartTrigger.Kind kind) {

    return (kind == BpmsStartTrigger.Kind.TIMER) || (kind == BpmsStartTrigger.Kind.SIGNAL) || (kind == BpmsStartTrigger.Kind.CONDITIONAL);

  }

  /**
   * Ends the boot where the BPMS can start the process and the application did not say how.
   * The message names the process, the start event and the method to write, so that a
   * developer can copy it from the log.
   * <p>
   * This is judged per START EVENT, not per process. A process with a timer start and a
   * signal start may have a method for one and none for the other. The one without a method
   * would fire into nothing. A method which serves every start event of the process
   * ({@code @WorkflowStartedByBpms} without an id) covers all of them.
   * <p>
   * The version which a method serves is not read here. A method kept for an older version
   * of the model still says that the application knows about this start event. Refusing a
   * boot because of a version range would be a second verdict on top of the one which the
   * startup check about dead handlers already makes.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param startEvents The start events the adapters reported for the process
   * @param handlers The methods registered for this process
   * @param workflowAggregateClass The aggregate which the method has to return, or
   *          <code>null</code> where it is not known here
   */
  private static void refuseStartEventsWithoutAMethod(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmsInitiatedStartSpec> startEvents,
      final List<BpmsInitiatedStartHandler> handlers,
      final Class<?> workflowAggregateClass) {

    final var unserved = startEvents
        .stream()
        // only a start event which the BPMS fires by itself is certain to arrive without a
        // name. A none or message start event can be the application's own start.
        // Demanding a method for those would refuse every ordinary process at boot
        .filter(spec -> firedWithoutTheApplication(spec.kind()))
        .filter(spec -> handlers
            .stream()
            .noneMatch(handler -> handler.matchesStartEvent(spec.elementId())))
        .toList();
    if (unserved.isEmpty()) {
      return;
    }
    final var aggregateName = workflowAggregateClass != null
        ? workflowAggregateClass.getSimpleName()
        : "YourWorkflowAggregate";
    throw new IllegalStateException(
        """
            BPMN process '%s' of workflow module '%s' has start event(s) the BPMS fires on its \
            own, and no @WorkflowStartedByBpms method builds the workflow aggregate for them: %s.

            Such a workflow has no aggregate until your code builds one, and nobody is there to \
            ask when the timer fires. Write the method in the @WorkflowService class of this \
            process:

              @WorkflowStartedByBpms(id = "%s")
              public %s buildAggregate(final BpmsStartTrigger trigger) {
                return new %s(...);
              }

            The id may be left out where one method serves every start event of the process. The \
            trigger says which start event fired; the ID of the aggregate is yours to choose, and \
            it is the name the workflow goes by in the BPMS."""
            .formatted(
                bpmnProcessId,
                workflowModuleId,
                describeStartEvents(unserved),
                unserved.getFirst().elementId(),
                aggregateName,
                aggregateName));

  }

  /**
   * Why a workflow which reached this application without a name cannot be built. Either
   * the process has no <code>&#64;WorkflowStartedByBpms</code> method at all, or none of its
   * methods serves the version which the BPMS reported. Both texts tell the reader the next
   * step, because a start refused at three in the morning has to explain itself.
   */
  private static <A> String startNobodyBuilds(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context,
      final List<BpmsInitiatedStartHandler> wired) {

    final var aggregateName = processService.getWorkflowAggregateClass().getSimpleName();
    if (wired.isEmpty()) {
      return ("The start of BPMN process '%s' of workflow module '%s' at start event '%s' is refused: "
          + "this workflow reached the application without VanillaBP starting it, and no "
          + "@WorkflowStartedByBpms method builds a workflow aggregate for it. Either start this "
          + "workflow through ProcessService, or, where it is meant to be started past VanillaBP, "
          + "write the method in the @WorkflowService class of this process:%n%n"
          + "  @WorkflowStartedByBpms(id = \"%s\")%n"
          + "  public %s buildAggregate(final BpmsStartTrigger trigger) {%n"
          + "    return new %s(...);%n"
          + "  }%n%n"
          + "The id may be left out where one method serves every start event of the process, and "
          + "the aggregate it returns carries the id the workflow goes by. Refusing is all "
          + "VanillaBP does here - what follows is what this BPMS does with any failing handler.")
          .formatted(
              processService.getBpmnProcessId(),
              processService.getWorkflowModuleId(),
              context.getStartEventId(),
              context.getStartEventId(),
              aggregateName,
              aggregateName);
    }
    return ("The start of BPMN process '%s' of workflow module '%s' at start event '%s' is refused: "
        + "this workflow reached the application without VanillaBP starting it, and no "
        + "@WorkflowStartedByBpms method serves process version '%s', so nothing builds its workflow "
        + "aggregate. Methods wired to that start event: %s.%s")
        .formatted(
            processService.getBpmnProcessId(),
            processService.getWorkflowModuleId(),
            context.getStartEventId(),
            context.getProcessVersion(),
            describeHandlers(wired),
            io.vanillabp.integration.adapter.migration.workflowtask.VersionRange
                .noVersionReportedHint(
                    context.getProcessVersion(),
                    wired.stream().anyMatch(BpmsInitiatedStartHandler::inheritsVersions)));

  }

  private static String describeHandlers(
      final List<BpmsInitiatedStartHandler> handlers) {

    return handlers
        .stream()
        .map(handler -> "'%s' (%s)".formatted(handler.describe(), handler.describeWiring()))
        .collect(Collectors.joining(", "));

  }

  /**
   * Names the start events which the BPMS reported, for a message which has to say which
   * ones were there. A signal event shows its name after its kind. Without the name, the
   * reader would have to look up an element id in the model.
   *
   * @param startEvents The start events the adapter reported
   * @return One text naming every event
   */
  private static String describeStartEvents(
      final List<BpmsInitiatedStartSpec> startEvents) {

    return startEvents
        .stream()
        .map(BpmsInitiatedStarts::describeStartEvent)
        .collect(Collectors.joining(", "));

  }

  private static String describeStartEvent(
      final BpmsInitiatedStartSpec spec) {

    return spec.signalName() == null
        ? "'%s' (%s)".formatted(spec.elementId(), spec.kind())
        : "'%s' (%s: %s)".formatted(spec.elementId(), spec.kind(), spec.signalName());

  }

}
