package io.vanillabp.integration.adapter.migration.workflowstart;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.processservice.AggregateIdRoundTrip;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * Decides what a start which the BPMS reported means. Where the answer is a workflow which
 * nobody started through VanillaBP, it lets the APPLICATION build the workflow aggregate.
 * <p>
 * The decision reads the STATE of the workflow, not the kind of its start event. That is
 * why an adapter reports every start event of a process:
 * <ul>
 * <li>The BPMS holds a name for this workflow, and a workflow aggregate has it. Then the
 * workflow is already ours, and nothing is built. That is the application's own start. It
 * also keeps a second delivery of the same notification from building a second
 * aggregate.</li>
 * <li>The BPMS holds no name. Then somebody started this workflow without VanillaBP. The
 * <code>&#64;WorkflowStartedByBpms</code> method builds the aggregate and names it, and the
 * adapter writes that name into the BPMS.</li>
 * <li>The BPMS holds a name which no workflow aggregate has. Then the workflow was started
 * under a name which VanillaBP did not give it, and the start is refused.</li>
 * </ul>
 * VanillaBP builds nothing itself. An object which comes into existence without the
 * application does not have the application's values. For a workflow which nobody started
 * through VanillaBP, that would be the very first thing that ever happens to it.
 * <p>
 * Decision 69 in the repository's DECISIONS.md has the rule and what it replaced.
 */
public final class BpmsInitiatedStartExecution {

  private static final Logger log = LoggerFactory.getLogger(BpmsInitiatedStartExecution.class);

  private BpmsInitiatedStartExecution() {
  }

  /**
   * Runs that decision for one notification of the BPMS, in the transaction the adapter
   * asked for. A notification may arrive twice, for example from a retried listener job or a
   * replayed engine transaction. Then the second one finds the workflow named in the BPMS by
   * the first attempt, and it builds nothing. So business data written in the meantime
   * survives.
   *
   * @param <A> The workflow-aggregate type
   * @param processService The process service of the BPMN process (persistence, ID
   *          type, aggregate class)
   * @param handler The application's method which builds the aggregate. It is used only
   *          where the workflow turns out to be one which nobody started through VanillaBP.
   *          It is <code>null</code> where the process has no method for this start
   * @param refusalWithoutAMethod What to say where such a workflow arrives and
   *          <code>handler</code> is <code>null</code>
   * @param context The adapter's notification
   * @param transactionRunner The platform's transaction runner
   * @return The aggregate's ID and the variables the adapter writes back (the aggregate-ID
   *         variable; the caller adds the shared aggregate values)
   */
  public static <A> BpmsInitiatedStartResult run(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartHandler handler,
      final String refusalWithoutAMethod,
      final BpmsInitiatedStartContext context,
      final TransactionRunner transactionRunner) {

    final var nameTheBpmsHolds = nameTheBpmsHolds(processService, context);
    final Supplier<BpmsInitiatedStartResult> transactionalWork = () -> decide(
        processService,
        handler,
        refusalWithoutAMethod,
        context,
        nameTheBpmsHolds);

    // the started instance is the activation in which the application's handler runs. So
    // what it plans can be told apart from what a second firing of the same start event
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
              nameTheBpmsHolds,
              "the start at start event '%s'".formatted(context.getStartEventId()),
              transactionalWork);
    }

  }

  /**
   * Answers the start of a called process, which is no workflow of its own. Nothing is built
   * and no start row is written. The instance runs on the workflow aggregate of the process
   * which called it. The id of that aggregate is all the adapter gets back.
   * <p>
   * Only a model deployed before adapters asked
   * {@link io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker#startsAWorkflowOfItsOwn}
   * still reports such a start, because it has the start listener of that time.
   *
   * @param <A> The workflow-aggregate type
   * @param processService The process service of the called process
   * @param context The adapter's notification
   * @return The id the instance already carries, as the variables the adapter writes back
   * @throws IllegalStateException Where the instance has no id. A called process which does
   *           not know its caller's aggregate cannot run any task
   */
  public static <A> BpmsInitiatedStartResult theStartOfACalledProcess(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context) {

    final var aggregateIdName = aggregateIdNameIfAnswered(processService);
    final var nameTheBpmsHolds = (context.getBusinessKey() != null) || (aggregateIdName != null)
        ? nameTheBpmsHolds(processService, context)
        : null;
    if (nameTheBpmsHolds == null) {
      throw new IllegalStateException(
          """
              The called process '%s' of workflow module '%s' started at start event '%s'%s without \
              the id of a workflow aggregate! It is declared in 'secondaryBpmnProcesses', so it \
              runs on the aggregate of the process which called it, and VanillaBP builds no \
              aggregate for it. Let the call activity hand over the id of the caller's workflow \
              aggregate%s."""
              .formatted(
                  processService.getBpmnProcessId(),
                  processService.getWorkflowModuleId(),
                  context.getStartEventId(),
                  describeOrigin(context),
                  aggregateIdName == null
                      ? ""
                      : " in the process variable '%s'".formatted(aggregateIdName)));
    }
    log
        .debug(
            "The called process '{}' (workflow module '{}') started for workflow '{}' - it is a step of "
                + "the workflow which called it, so nothing is built and no start is written down",
            processService.getBpmnProcessId(),
            processService.getWorkflowModuleId(),
            nameTheBpmsHolds);
    final Map<String, Object> variables = new LinkedHashMap<>();
    if (aggregateIdName != null) {
      variables.put(aggregateIdName, nameTheBpmsHolds);
    }
    return new BpmsInitiatedStartResult(nameTheBpmsHolds, aggregateIdName, variables, false);

  }

  /**
   * The name which the BPMS already has for this workflow, wherever that BPMS keeps it.
   * <p>
   * Camunda 7 keeps it as the business key. A BPMS without a business key of its own, such
   * as Camunda 8 or the Process-Engine-API, keeps it in the process variable named after the
   * id attribute of the workflow aggregate. Every other part of VanillaBP reads it under that
   * name there as well. Asking both, in this order, lets one rule serve every BPMS.
   *
   * @return The name the BPMS holds, or <code>null</code> where it holds none
   */
  private static <A> String nameTheBpmsHolds(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context) {

    final var businessKey = context.getBusinessKey();
    if ((businessKey != null) && !businessKey.isBlank()) {
      return businessKey;
    }
    final var fromVariable = context.getVariables().get(processService.getAggregateIdName());
    if (fromVariable == null) {
      return null;
    }
    final var asText = String.valueOf(fromVariable);
    return asText.isBlank()
        ? null
        : asText;

  }

  /**
   * Where that name is kept, in the words of a message. So a reader knows which value of
   * their workflow a refusal is about.
   */
  private static <A> String whereTheNameIsKept(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context) {

    final var businessKey = context.getBusinessKey();
    return (businessKey != null) && !businessKey.isBlank()
        ? "its business key"
        : "the process variable '%s'".formatted(processService.getAggregateIdName());

  }

  private static <A> BpmsInitiatedStartResult decide(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartHandler handler,
      final String refusalWithoutAMethod,
      final BpmsInitiatedStartContext context,
      final String nameTheBpmsHolds) {

    if (nameTheBpmsHolds != null) {
      return theWorkflowTheBpmsNamed(processService, context, nameTheBpmsHolds);
    }
    if (handler == null) {
      throw new IllegalStateException(refusalWithoutAMethod);
    }

    final var aggregateClass = processService.getWorkflowAggregateClass();
    final Object returned;
    // a report about the aggregate which the method builds would ask about a workflow which
    // nobody can find yet. The start reports its values anyway once the method returned
    try (var running = io.vanillabp.integration.adapter.migration.processservice.RunningBpmsInitiatedStart
        .of(processService.getWorkflowModuleId(), processService.getBpmnProcessId())) {
      returned = handler.invoke(context);
    }
    if (returned == null) {
      throw new IllegalStateException(
          """
              The @WorkflowStartedByBpms method '%s' returned null! Return the workflow aggregate \
              of the workflow which reached this application without VanillaBP starting it (BPMN \
              process '%s' of workflow module '%s', start event '%s') - without it the workflow has \
              no data at all."""
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
        // the application named the workflow after something it already knows, and a
        // workflow of that name is running. Saving the new object would overwrite business
        // data
        log
            .debug(
                "The workflow aggregate '{}' of the started workflow of BPMN process '{}' "
                    + "(workflow module '{}', start event '{}') exists already - nothing is saved",
                chosenId,
                processService.getBpmnProcessId(),
                processService.getWorkflowModuleId(),
                context.getStartEventId());
        return result(processService, context, existing, false);
      }
    }

    final var attached = processService.saveWorkflowAggregate(workflowAggregate);
    final var aggregateId = processService.getWorkflowAggregateId(attached);
    if ((aggregateId == null) || aggregateId.toString().isBlank()) {
      throw new IllegalStateException(
          """
              The ID of the workflow aggregate of class '%s' is null or blank after saving the \
              started workflow (BPMN process '%s' of workflow module '%s', start event '%s')! The \
              ID names the workflow in the BPMS - assign one in the @WorkflowStartedByBpms method \
              '%s', or use a generated ID which the persistence layer assigns on save."""
              .formatted(
                  aggregateClass.getName(),
                  processService.getBpmnProcessId(),
                  processService.getWorkflowModuleId(),
                  context.getStartEventId(),
                  handler.describe()));
    }
    return result(processService, context, attached, true);

  }

  /**
   * What to do with a workflow for which the BPMS already has a name. Nothing, where a
   * workflow aggregate has that name. A refusal, where none has it.
   * <p>
   * A name which does not even fit the type of the id attribute, such as a text for a
   * numeric id, cannot be the id of any workflow aggregate. So it names none, and it is
   * refused like every other name which nothing has.
   */
  private static <A> BpmsInitiatedStartResult theWorkflowTheBpmsNamed(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context,
      final String nameTheBpmsHolds) {

    final var existing = AggregateIdRoundTrip
        .convertIfItFits(nameTheBpmsHolds, processService.getAggregateIdType())
        .map(processService::loadWorkflowAggregateById)
        .orElse(null);
    if (existing == null) {
      throw new IllegalStateException(
          ("The start of BPMN process '%s' of workflow module '%s' at start event '%s' is refused: the "
              + "BPMS knows this workflow as '%s' (%s) and no workflow aggregate of class '%s' carries "
              + "that id%s. VanillaBP names a workflow and nobody else: the id of a workflow is the id "
              + "of its workflow aggregate, and the application gives it in a @WorkflowStartedByBpms "
              + "method. Either start this workflow through ProcessService, which writes that id into "
              + "the BPMS, or start it without a name of your own and let that method name it. "
              + "Refusing is all VanillaBP does here - what follows is what this BPMS does with any "
              + "failing handler.")
              .formatted(
                  processService.getBpmnProcessId(),
                  processService.getWorkflowModuleId(),
                  context.getStartEventId(),
                  nameTheBpmsHolds,
                  whereTheNameIsKept(processService, context),
                  processService.getWorkflowAggregateClass().getName(),
                  describeOrigin(context)));
    }
    // the workflow is already ours. The application started it, or the BPMS reported this
    // start before (a retried listener job, a replayed engine transaction)
    log
        .debug(
            "The workflow of BPMN process '{}' (workflow module '{}') started at start event '{}' is "
                + "named '{}' in the BPMS and that workflow aggregate exists - nothing is built",
            processService.getBpmnProcessId(),
            processService.getWorkflowModuleId(),
            context.getStartEventId(),
            nameTheBpmsHolds);
    return result(processService, context, existing, false);

  }

  /**
   * Which BPMS a refused start came from, and which workflow it is there. So an operator
   * can look up the workflow where it is stuck. Both values are optional. Where an adapter
   * reports neither, the message says nothing about them.
   */
  private static String describeOrigin(
      final BpmsInitiatedStartContext context) {

    final var origin = new LinkedList<String>();
    if (context.getAdapterId() != null) {
      origin.add("adapter '%s'".formatted(context.getAdapterId()));
    }
    if (context.getNativeInstanceId() != null) {
      origin.add("workflow '%s' in the BPMS".formatted(context.getNativeInstanceId()));
    }
    return origin.isEmpty()
        ? ""
        : " (%s)".formatted(String.join(", ", origin));

  }

  /**
   * The aggregate's id and the variables which the adapter writes back. This is also where
   * the pair "this aggregate, that workflow" is written down.
   * <p>
   * The id variable travels even where nothing was built. An adapter which completes a
   * listener job writes what it is given. And writing the name which a workflow already has
   * changes nothing about it.
   */
  private static <A> BpmsInitiatedStartResult result(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context,
      final A workflowAggregate,
      final boolean created) {

    final var aggregateIdName = aggregateIdNameIfAnswered(processService);
    final var serializedId = String.valueOf(processService.getWorkflowAggregateId(workflowAggregate));
    // the aggregate and the workflow which runs it are both known right here, in the
    // transaction which persists the aggregate. So this is where the pair is written down.
    // It happens for every outcome of the decision above, also for the workflow which was
    // ours already. The row is keyed by the aggregate, so writing it again writes nothing.
    // The version which the BPMS reported with the start goes with it. That is where an
    // adapter of a remote BPMS knows the version without asking anybody
    processService
        .recordWorkflowStart(
            context.getAdapterId(),
            serializedId,
            context.getNativeInstanceId(),
            context.getProcessVersion());
    final Map<String, Object> variables = new LinkedHashMap<>();
    if (aggregateIdName != null) {
      variables.put(aggregateIdName, serializedId);
    }
    return new BpmsInitiatedStartResult(serializedId, aggregateIdName, variables, created);

  }

  /**
   * The name of the aggregate's ID property, where the persistence answers it. A BPMS with a
   * business key of its own, such as Camunda 7, keeps the id there and reads no variable. So
   * an application on it does not need to implement
   * {@code AggregatePersistenceAware#getAggregateIdName}. A BPMS without a business key needs
   * the name already while the model is wired. If the persistence does not answer, the start
   * of such an application ends there.
   *
   * @param processService The process service of the workflow
   * @return The name, or <code>null</code> where the persistence does not answer it
   */
  private static String aggregateIdNameIfAnswered(
      final MigrationProcessService<?> processService) {

    try {
      return processService.getAggregateIdName();
    } catch (final UnsupportedOperationException e) {
      return null;
    }

  }

}
