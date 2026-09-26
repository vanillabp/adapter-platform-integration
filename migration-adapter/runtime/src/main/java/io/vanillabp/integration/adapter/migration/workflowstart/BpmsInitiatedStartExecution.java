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
 * Decides what a start the BPMS reported means, and lets the APPLICATION build the
 * workflow aggregate where the answer is a workflow nobody started through VanillaBP.
 * <p>
 * The decision reads the STATE of the workflow and not the kind of its start event, which
 * is why an adapter reports every start event of a process:
 * <ul>
 * <li>The BPMS holds a name for this workflow and a workflow aggregate carries it: the
 * workflow is already ours and nothing is built. That is the application's own start, and
 * it is also what keeps a second delivery of the same notification from building a second
 * aggregate.</li>
 * <li>The BPMS holds no name: somebody started this workflow past VanillaBP. The
 * <code>&#64;WorkflowStartedByBpms</code> method builds the aggregate and names it, and the
 * adapter writes that name into the BPMS.</li>
 * <li>The BPMS holds a name no workflow aggregate carries: the workflow was started under a
 * name VanillaBP did not give it, and the start is refused.</li>
 * </ul>
 * VanillaBP builds nothing itself. An object which comes into existence without the
 * application does not carry the application's values, and for a workflow nobody started
 * through VanillaBP that would be the very first thing that ever happens to it.
 * <p>
 * The rule and what it replaced are {@code DECISIONS.pending/653.md}.
 */
public final class BpmsInitiatedStartExecution {

  private static final Logger log = LoggerFactory.getLogger(BpmsInitiatedStartExecution.class);

  private BpmsInitiatedStartExecution() {
  }

  /**
   * Runs that decision for one notification of the BPMS, in the transaction the adapter
   * asked for. A notification which arrives twice - a retried listener job, a replayed
   * engine transaction - finds the workflow named in the BPMS by the first attempt and
   * builds nothing, so business data written meanwhile survives.
   *
   * @param <A> The workflow-aggregate type
   * @param processService The process service of the BPMN process (persistence, ID
   *          type, aggregate class)
   * @param handler The application's method building the aggregate, used only where the
   *          workflow turns out to be one nobody started through VanillaBP, and
   *          <code>null</code> where the process has no method for this start
   * @param refusalWithoutAMethod What to say where such a workflow arrives and
   *          <code>handler</code> is <code>null</code>
   * @param context The adapter's notification
   * @param transactionRunner The platform's transaction runner
   * @return The aggregate's ID and the variables the adapter writes back (the
   *         aggregate-ID variable; shared aggregate values are added by the caller)
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
              nameTheBpmsHolds,
              "the start at start event '%s'".formatted(context.getStartEventId()),
              transactionalWork);
    }

  }

  /**
   * The name the BPMS already has for this workflow, wherever that BPMS keeps it.
   * <p>
   * Camunda 7 keeps it as the business key. A BPMS without a business key of its own -
   * Camunda 8, the Process-Engine-API - keeps it in the process variable named after the
   * workflow aggregate's id attribute, which is the name every other part of VanillaBP
   * reads it under there as well. Asking both, in this order, is what lets one rule serve
   * every BPMS.
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
   * Where that name is kept, in the words of a message, so a reader knows which value of
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
    final var returned = handler.invoke(context);
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
        // workflow of that name is running - saving the new object would overwrite
        // business data
        log
            .debug(
                "The workflow aggregate '{}' of the started workflow of BPMN process '{}' "
                    + "(workflow module '{}', start event '{}') exists already - nothing is saved",
                chosenId,
                processService.getBpmnProcessId(),
                processService.getWorkflowModuleId(),
                context.getStartEventId());
        return result(processService, existing, false);
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
    return result(processService, attached, true);

  }

  /**
   * What to do with a workflow the BPMS already has a name for: nothing where a workflow
   * aggregate carries that name, and a refusal where none does.
   * <p>
   * A name which does not even fit the type of the id attribute - a text against a numeric
   * id - can be no workflow aggregate's id, so it names none and is refused like every
   * other name nothing carries.
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
    // the workflow is already ours: the application started it, or the BPMS reported
    // this start before (a retried listener job, a replayed engine transaction)
    log
        .debug(
            "The workflow of BPMN process '{}' (workflow module '{}') started at start event '{}' is "
                + "named '{}' in the BPMS and that workflow aggregate exists - nothing is built",
            processService.getBpmnProcessId(),
            processService.getWorkflowModuleId(),
            context.getStartEventId(),
            nameTheBpmsHolds);
    return result(processService, existing, false);

  }

  /**
   * Which BPMS a refused start came from and which workflow it is there, so an operator can
   * look the workflow up where it is stuck. Both values are optional, and where an adapter
   * reports neither the message says nothing about them.
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
   * The aggregate's id and the variables the adapter writes back.
   * <p>
   * The id variable travels even where nothing was built: an adapter completing a listener
   * job writes what it is handed, and writing the name a workflow already has changes
   * nothing about it.
   */
  private static <A> BpmsInitiatedStartResult result(
      final MigrationProcessService<A> processService,
      final A workflowAggregate,
      final boolean created) {

    final var aggregateIdName = processService.getAggregateIdName();
    final var serializedId = String.valueOf(processService.getWorkflowAggregateId(workflowAggregate));
    final Map<String, Object> variables = new LinkedHashMap<>();
    variables.put(aggregateIdName, serializedId);
    return new BpmsInitiatedStartResult(serializedId, aggregateIdName, variables, created);

  }

}
