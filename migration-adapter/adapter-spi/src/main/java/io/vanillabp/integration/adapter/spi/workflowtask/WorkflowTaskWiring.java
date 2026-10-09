package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.Collection;
import java.util.List;

/**
 * What a BPMS adapter calls back into VanillaBP's core WHILE IT DEPLOYS, implemented by
 * the core (the migration adapter) and handed to adapters by the platform integration.
 * The runtime counterpart is {@link WorkflowTaskInvoker}, which the adapter's worker
 * threads hold - the two were one interface of thirty methods until it became clear that
 * a mandatory call an adapter can forget WILL be forgotten by the next adapter (Camunda 7
 * forgot {@link #validateNoUnwiredWorkflowTaskMethods(String)} for a year, and a typo in
 * a task definition stayed silent until a workflow reached the task).
 *
 * <h2>What is due when</h2>
 *
 * Per BPMN process, while <code>wireBpmn</code> runs - the adapter is the only one which
 * can read its own BPMN dialect, so everything the core needs about a model arrives here:
 * <ul>
 * <li>{@link #validateTaskWiring(String, String, String, Collection)} - every BPMN task has
 * a <code>&#64;WorkflowTask</code> method or is marked as served elsewhere. Throwing from
 * <code>wireBpmn</code> honors the <code>deployment-failure</code> policy;</li>
 * <li>{@link #taskParameterNames(String, String, String)} - if your BPMS ships a variable
 * payload with a delivery, you have to know the names BEFORE you subscribe;</li>
 * <li>{@link #extensionTaskParameterNames(String, String, List)} - the same for the
 * methods of the extensions, if your BPMS hands a task to one channel only;</li>
 * <li>{@link #multiInstanceElementNames(String, String, String)} - which iterations a
 * handler wants the item of, so a model handing over none is refused while it is
 * deployed;</li>
 * <li>{@link #workflowTaskCompletesAsynchronously(String, String, String)} - refuse a
 * wiring which cannot keep a task open;</li>
 * <li>{@link #workflowTaskCompletesAsynchronously(String, String, String)} and
 * {@link #workflowsShareTheWorkflowAggregate(String, String, String)} - what the model
 * has to be rewritten for;</li>
 * <li>{@link #reportConcurrentTokenElements(String, String, Collection)} - the elements
 * which can put a second token into a workflow;</li>
 * <li>{@link #reportCompensation(String, String, Collection)} - the compensation throw
 * events which start more than one handler, which is the same second token drawn
 * differently;</li>
 * <li>{@link #reportModelExpressions(String, String, Collection)} - the expressions the
 * model reads the workflow's data with;</li>
 * <li>{@link #registerProcessVersions(String, String, String, ProcessVersionCatalog)} -
 * only where your BPMS can place version tags;</li>
 * <li>{@link #reportNoProcessVersionCatalog(String, String, String, io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion)} -
 * where it cannot, and there is nothing to place them in;</li>
 * <li>{@link #unsharedWorkflowAggregateProperties(String, String, Collection, io.vanillabp.integration.adapter.spi.AggregateSyncMode)} -
 * what a model reads but the aggregate does not share.</li>
 * </ul>
 * At the end of <code>deployResources</code>, per BPMN process:
 * {@link #registerDeployedVersion(String, String, String, String)} - also when your BPMS
 * deployed nothing because nothing changed. Only the adapter knows which version its BPMS
 * ended up with, which is why this one stays here.
 * <p>
 * What an adapter may ASK at any point of the pipeline, rather than having to report:
 * {@link #taskWiringOfProcessesNobodyDeployed(String)} - what the application's methods
 * serve for a BPMN process it declares without bringing a model, which is what a renamed
 * BPMN process leaves behind.
 * <p>
 * <b>What the core does on its own</b>, once the last adapter of a workflow module
 * finished deploying: {@link #validateNoUnwiredWorkflowTaskMethods(String)},
 * {@link #registerVersionsOfProcessesNobodyDeployed(String, String, java.util.function.BiFunction)},
 * {@link #resolveProcessVersions(String)}, {@link #reportExtensionHandlerWiring(String)},
 * and {@link #reportWhatACancelationCannotCarry(String)}. All of them are module-level
 * and answered from what the application declared next to what the adapters wired, so the
 * core knows the moment and takes the duty - an adapter must NOT call them.
 * <p>
 * <b>A process nobody claims</b> is one no <code>&#64;WorkflowService</code> class declares
 * ({@link #isClaimedByAWorkflowService(String, String)} answers <code>false</code>). It is
 * deployed only because it shares a file with a claimed one, and an adapter leaves it alone:
 * no change of its model beyond what the whole file needs, no worker, no listener, no
 * subscription, and no check of its own which ends the start because of it. The core ends the
 * start over such a process before anything is deployed, unless the application marked it as
 * somebody else's with
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.implemented-externally=true</code>.
 * A process which is not in a deployed file at all is not VanillaBP's business either.
 */
public interface WorkflowTaskWiring {

  /**
   * Validates that every given BPMN task is served by a
   * <code>&#64;WorkflowTask</code> method of the process' workflow service(s), or is marked
   * as served by something else with <code>implemented-externally=true</code> (see
   * {@link ImplementedExternally}). A user task is no exception: version 1 asked for a
   * method for it as well. A line above the task, at the workflow or higher, covers only the
   * tasks without a method; a line at the task itself next to a method is a contradiction. All
   * tasks which are neither served nor marked, and all which are served and marked at the task,
   * are collected and reported in ONE exception with guiding messages. Additionally every
   * matched method is marked as wired - the input for
   * {@link #validateNoUnwiredWorkflowTaskMethods(String)}.
   * <p>
   * Call this for the claimed processes only. A process NO workflow service claims is not
   * validated: a BPMN file travels to the BPMS as a whole, so a process drawn next to the
   * one the application asked for is deployed with it, and demanding methods for a model
   * somebody else owns would stop the application over a file it cannot change. What
   * happens to such a process is written at the type.
   * <p>
   * The adapter id is what lets a task be marked for one adapter only, the two adapters of
   * a migration being the reason.
   *
   * @param adapterId The id of the adapter deploying the model
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param tasks The tasks of the executable BPMN process to be wired
   * @throws IllegalStateException If a BPMN task of a CLAIMED process has no matching
   *           method and is not marked as served elsewhere, or has one and is marked at the task
   *           itself
   */
  default void validateTaskWiring(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmnTaskSpec> tasks) {

    validateTaskWiring(workflowModuleId, bpmnProcessId, tasks);

  }

  /**
   * {@link #validateTaskWiring(String, String, String, Collection)} for an adapter which
   * does not name itself. A task marked for one adapter only is not seen as marked then.
   * The default of the four-argument method calls this one, which keeps a test double of
   * this SPI compiling.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param tasks The tasks of the executable BPMN process to be wired
   * @throws IllegalStateException If a BPMN task of a CLAIMED process has no matching
   *           method and is not marked as served elsewhere, or has one and is marked at the task
   *           itself
   */
  void validateTaskWiring(
      String workflowModuleId,
      String bpmnProcessId,
      Collection<BpmnTaskSpec> tasks);

  /**
   * Whether the application says that something other than itself serves this task:
   * <code>implemented-externally=true</code>, written for the element id or for the task
   * definition, with the element id winning where both are written (see
   * {@link ImplementedExternally}).
   * <p>
   * An adapter asks this where it refuses a shape on its own before the core sees the task,
   * a Camunda 7 external task say: such a task is not refused once the application says
   * that somebody else serves it. Whatever the answer, the task still goes to
   * {@link #validateTaskWiring(String, String, String, Collection)}, which holds the rule.
   * The default answers <code>false</code>, which keeps a test double of this SPI
   * compiling.
   *
   * @param adapterId The id of the adapter asking
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param task The task
   * @return Whether the task is marked as served elsewhere
   */
  default boolean isImplementedExternally(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnTaskSpec task) {

    return false;

  }


  /**
   * Reports the elements of a BPMN process which can put a SECOND token into a
   * running workflow - a non-interrupting boundary event, a parallel or inclusive
   * gateway forking into several flows, a parallel multi-instance activity, a
   * non-interrupting event subprocess, an ad-hoc subprocess. Called during
   * <code>wireBpmn</code>, since only the adapter can read its BPMN dialect.
   * <p>
   * The ad-hoc subprocess belongs on that list whatever the model around it says. Which
   * of its activities run is decided while the workflow already stands there, so a model
   * activating one activity today activates two as soon as the data behind that choice
   * changes. A warning which appears only after such a change is worse than one which
   * appears always.
   * <p>
   * What it means is the core's decision: concurrent tokens mean two
   * branches writing the same workflow aggregate, and an aggregate without a version
   * attribute loses the writes of whichever branch commits first, without any error.
   * The core knows the aggregate class, so it warns once per BPMN process - naming
   * the elements reported here, which is why this method takes IDs rather than a
   * boolean.
   * <p>
   * An adapter whose BPMS cannot be asked about its models reports nothing; the check
   * stays silent then instead of guessing.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param elementIds The IDs of the elements producing a second token
   */
  default void reportConcurrentTokenElements(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> elementIds) {

  }

  /**
   * Reports the compensation throw events of a BPMN process which start MORE THAN ONE
   * handler. Called during <code>wireBpmn</code>, next to
   * {@link #reportConcurrentTokenElements(String, String, Collection)} and for the same
   * reason: only the adapter can read its BPMN dialect.
   * <p>
   * Compensation is the second token drawn differently. A throw event which compensates
   * two finished activities starts both handlers, the workflow holds a token per handler,
   * and each of them writes the workflow aggregate - so it belongs on the list of things
   * an aggregate without a version attribute cannot survive. It is reported separately
   * because the finding has a shape the flat list cannot carry: the developer has to read
   * WHICH throw event starts WHICH handlers, and the throw event alone says nothing.
   * <p>
   * A throw event starting one handler is left out. The compensation then runs where every
   * other activity of the model runs, on the one token the workflow already has.
   * <p>
   * Whether the handlers run one after the other or next to each other is the engine's
   * answer and differs. Camunda 8 hands out both handler jobs at once; Camunda 7 starts
   * them one after the other in the transaction of the throw event, measured on 7.24 - but
   * a handler which WAITS keeps its token while the next one is started there too, so the
   * report is the same on both.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param compensations The throw events starting several handlers, with those handlers
   */
  default void reportCompensation(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<CompensationSpec> compensations) {

  }

  /**
   * Reports the expressions a BPMN process reads the workflow's data with, so the core can
   * say what the ones which are more than the name of a variable cost. Called during
   * <code>wireBpmn</code>, ONCE per BPMN process and with ALL of them, the plain names
   * included: the message counts them, and a developer reading that five of seven
   * expressions already name a variable learns how far their model is.
   * <p>
   * Finding them is the adapter's half of the work, because the places and the language
   * belong to the BPMS. Judging them is the core's, because the answer has to be the same
   * whichever BPMS a model is deployed to - an expression which walks a path binds the
   * shape of the application's data everywhere.
   * <p>
   * Report what reads the workflow's DATA: the conditions of sequence flows and of
   * conditional events, timers, the cardinality, the collection and the completion
   * condition of a multi-instance element, a loop condition, the correlation key of a
   * message, the inputs of a decision, an input or output mapping. Leave out what the BPMS
   * resolves for itself - an expression naming a wired task, a delegate class, a form key.
   * An adapter which cannot read its models reports nothing, and nothing is read into that
   * silence.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param expressions The expressions of that process, each with the element it sits in
   *          and the place inside it
   */
  default void reportModelExpressions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<io.vanillabp.integration.adapter.spi.expressions.ModelExpression> expressions) {

  }

  /**
   * Validates - after ALL BPMN processes of a workflow module were wired - that
   * every <code>&#64;WorkflowTask</code> method matched a task of at least ONE of
   * the module's BPMN processes (a workflow service class may declare several
   * processes via {@code secondaryBpmnProcesses}, so a method unmatched in one
   * process may legitimately serve another - this check closes the second
   * direction the per-process {@link #validateTaskWiring} cannot decide). Called
   * by the adapter at the END of <code>deployResources</code>; throwing there
   * honors the <code>deployment-failure</code> policy.
   *
   * @param workflowModuleId The workflow module ID
   * @throws IllegalStateException Naming every method matching no task of any
   *           wired BPMN process, with the fix
   */
  void validateNoUnwiredWorkflowTaskMethods(
      String workflowModuleId);

  /**
   * Names the <code>&#64;WorkflowTask</code> methods which would be called with values
   * missing where VanillaBP works a cancellation out for itself, so a developer reads it at
   * the boot rather than in production.
   * <p>
   * A cancellation VanillaBP derives carries no job: the element is gone by the time
   * anybody notices, so
   * {@link TaskInvocationContext#getTaskParameter(String)} and
   * {@link TaskInvocationContext#getMultiInstances()} have nothing to answer from. A method
   * which binds neither is not affected and is not named. Called by the CORE once the module
   * is deployed; an adapter must not call it.
   * <p>
   * The default does nothing, which keeps a test double of this SPI compiling.
   *
   * @param workflowModuleId The workflow module which finished deploying
   */
  default void reportWhatACancelationCannotCarry(
      final String workflowModuleId) {

  }

  /**
   * Writes what the handler methods of the extensions were wired to in this workflow
   * module, the way the <code>&#64;WorkflowTask</code> side of a module is reported.
   * Called by the CORE once the module is deployed; an adapter must not call it.
   * <p>
   * The default does nothing, which keeps a test double of this SPI compiling.
   *
   * @param workflowModuleId The workflow module ID
   */
  default void reportExtensionHandlerWiring(
      final String workflowModuleId) {

    // the core answers this

  }

  /**
   * Which of the given names are attributes of the workflow aggregate that are NOT
   * shared with the BPMS - the question behind the startup check for such expressions.
   * <p>
   * An embedded engine can read the BPMN model, and only the core knows what an
   * aggregate shares. So the adapter collects the identifiers its models read (a
   * condition, a timer, a multi-instance collection) and asks here which of them would
   * always be <code>null</code> in the engine although the application clearly meant an
   * attribute of its aggregate. A name which is no attribute at all is none of this
   * check's business: it may well be a variable the model itself provides.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param names The identifiers read by the model
   * @param adapterDefault What this adapter shares unless the application says
   *          otherwise
   * @return The names which are attributes of the aggregate but not shared, in the
   *         order given; empty if the BPMN process is unknown
   * @see #unsharedWorkflowAggregatePaths(String, String, java.util.Collection,
   *      io.vanillabp.integration.adapter.spi.AggregateSyncMode) for an adapter which can
   *      read a whole path out of its model, which is the fuller question
   */
  default java.util.Collection<String> unsharedWorkflowAggregateProperties(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> names,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    return java.util.List.of();

  }

  /**
   * The same question for a PATH an expression reads
   * (<code>order.customer.address.city</code>): which of the given paths stop short of a
   * value the BPMS holds, and where.
   * <p>
   * An adapter which can read the whole path out of its model asks this instead of
   * {@link #unsharedWorkflowAggregateProperties}, which answers about the first segment
   * only. The nested case is the one worth asking about: the shared values are a
   * structure, so an expression navigating into them meets the sync model at every
   * segment, and an unshared segment two levels down reads <code>null</code> just as a
   * top-level one does.
   * <p>
   * A path whose FIRST segment is no attribute of the aggregate at all is NOT reported,
   * for the same reason the single-name question leaves it alone: the model may well
   * provide a variable of that name. Everything below the first segment is reported,
   * because there the aggregate is what the expression navigates.
   * <p>
   * The core answers from the DECLARED types of the segments and stays silent wherever
   * they cannot decide, so a path which is missing from the answer means either "this
   * works" or "this cannot be judged" - never "this was checked and found broken".
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param paths The paths read by the model, segments separated by dots
   * @param adapterDefault What this adapter shares unless the application says
   *          otherwise
   * @return The reportable paths with what the walk found, keyed by the path as it was
   *         given; empty if the BPMN process is unknown
   */
  default java.util.Map<String, io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict> unsharedWorkflowAggregatePaths(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> paths,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    return java.util.Map.of();

  }

  /**
   * Which type each of the given paths ends at, so an adapter can judge what its BPMS
   * makes of the value.
   * <p>
   * A BPMS stores what VanillaBP hands it, and the format it stores it in may hand
   * something else back. Whether that matters is the adapter's question, because only the
   * adapter knows which types its BPMS carries unchanged. The core knows the types and
   * answers them, one object per deployed process, the way it answers every other question
   * about a model.
   * <p>
   * A path is answered only where it reaches a value the BPMS holds. A path the sync model
   * cuts short is missing here, because such a value never leaves the application, and so
   * is a path the declared types cannot decide, which is the same silence
   * {@link #unsharedWorkflowAggregatePaths} keeps. The two questions read the same walk, so
   * a path reported there is never answered here.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param paths The paths read by the model, segments separated by dots
   * @param adapterDefault What this adapter shares unless the application says
   *          otherwise
   * @return The declared type per path, keyed by the path as it was given and holding
   *         only the paths which reach a value; empty if the BPMN process is unknown
   */
  default java.util.Map<String, Class<?>> declaredTypesOfWorkflowAggregatePaths(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> paths,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    return java.util.Map.of();

  }

  /**
   * The process variables the <code>&#64;WorkflowTask</code> method(s) serving the
   * given task definition (or BPMN activity ID) read with
   * <code>&#64;TaskParam</code> - the names as the annotation spells them.
   * <p>
   * A <code>&#64;TaskParam</code> is how the application reads what the BPMS
   * GENERATED on this path: a value an input or output mapping produced, the result
   * of a script or a decision, something the model computed rather than the
   * workflow aggregate holds. A BPMS which hands its worker a variable payload has
   * to know these names to keep that payload down to what is actually read, and the
   * core is the only place they exist - the adapter sees a
   * {@link TaskInvocationContext#getTaskParameter(String)} call one name at a time,
   * and only once the delivery is already there.
   * <p>
   * Several methods may serve one element (different process versions), so
   * the answer is the UNION of their parameters: the delivery has to satisfy
   * whichever of them runs. The names are sorted and duplicate-free, which is what a
   * subscription comparing itself across restarts needs (a Camunda 8 job stream is
   * equivalent to another one only if the fetched variables match).
   * <p>
   * The default answers nothing, which switches the derivation off rather than
   * making an adapter fetch an incomplete list.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinitionOrActivityId The task definition or BPMN activity ID
   * @return The declared parameter names, sorted; empty if no method is registered
   *         or none of them declares a <code>&#64;TaskParam</code>
   */
  default Collection<String> taskParameterNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    return java.util.List.of();

  }

  /**
   * The process variables the methods of the EXTENSIONS read with
   * <code>&#64;TaskParam</code> for one element, over every extension which registered
   * a handler contract - the names as the annotation spells them. The counterpart of
   * {@link #taskParameterNames(String, String, String)}, which answers for the
   * <code>&#64;WorkflowTask</code> methods only.
   * <p>
   * It is meant for a BPMS which hands a task to exactly ONE channel. The
   * Process-Engine-API is one: it delivers a task to one subscription, so an extension
   * gets no channel of its own and reads what the adapter's subscription delivered. That
   * subscription has to ask for what the extensions read as well, and the adapter cannot
   * ask each extension, because it does not know their annotations. A BPMS where an
   * extension opens a channel of its own (a Camunda 7 listener, a Camunda 8 job worker)
   * does not need this: the extension asks for its own variables there.
   * <p>
   * Pass the keys the way the extensions look an element up, most wanted first: the
   * BPMN element id, then the task definition. Each extension's methods are found by
   * walking those keys, and the answer is the UNION over every method which may run for
   * this element in some version. It may name a variable too many, but never misses one.
   * The names are sorted and duplicate-free, so a subscription built from them stays the
   * same across restarts. Ask this next to {@link #taskParameterNames}, not instead of
   * it, and fetch the union of both answers.
   * <p>
   * The answer covers the contracts registered and the workflow services scanned when it
   * is asked, so ask it as late as you can: when you open the channel, not earlier. The
   * default answers nothing, which keeps a test double compiled against an older core
   * unchanged.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param lookupKeys The keys an extension method may be matched by, most wanted first
   * @return The parameter names, sorted; empty if no extension registered a contract or
   *         none of their methods serving these keys declares a <code>&#64;TaskParam</code>
   */
  default Collection<String> extensionTaskParameterNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys) {

    return List.of();

  }

  /**
   * The BPMN elements the <code>&#64;WorkflowTask</code> method(s) serving the given
   * task definition (or BPMN activity ID) want the ITEM of an iteration for - the ids
   * <code>&#64;MultiInstanceElement</code> names, and where a resolver bean answers
   * instead, the ids
   * {@link io.vanillabp.spi.service.MultiInstanceElementResolver#getNames()} reports.
   * <p>
   * Of what a multi-instance element hands to a handler, the item is the part a model
   * may not carry at all: a Camunda 7 element without <code>camunda:elementVariable</code>
   * and a Camunda 8 one without <code>inputElement</code> iterate without ever naming
   * the value of the round. Such a model is legitimate, and so is a handler which reads
   * the index and the total only. The two together are not: the parameter receives
   * <code>null</code> and nothing says why.
   * <p>
   * Neither side sees both halves. Only the adapter reads the BPMN, and only the core
   * scans the handlers, so an adapter asks this while it deploys and refuses the pairing
   * its model cannot serve. The index and the total need no such question, which is why
   * this method is about the item alone.
   * <p>
   * An id which is no element of the model being deployed is NO finding. The
   * multi-instance chain crosses a call activity, so a task of a called process asks for
   * an element of its caller, and the model at hand is the wrong place to look for it.
   * <p>
   * Several methods may serve one element (different process versions), so the answer is
   * the UNION of what they ask for: they read their item out of the same model, whichever
   * of them runs. The ids are sorted and duplicate-free.
   * <p>
   * The default answers nothing, which switches such a check off rather than letting an
   * adapter judge a model against half the question.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinitionOrActivityId The task definition or BPMN activity ID
   * @return The BPMN element ids an item is asked for, sorted; empty if no method is
   *         registered or none of them declares a <code>&#64;MultiInstanceElement</code>
   */
  default Collection<String> multiInstanceElementNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    return java.util.List.of();

  }

  /**
   * Whether two BPMN processes of one workflow module work on the SAME workflow
   * aggregate - which is what the declaration says: one class declares the process
   * to be started as its {@code bpmnProcess} and the others as
   * {@code secondaryBpmnProcesses}.
   * <p>
   * Adapters ask this about a call activity: a process called on the same aggregate
   * continues the same business case and has to reach the same aggregate, whereas a
   * process with an aggregate of its own must not be handed the caller's identity.
   * Camunda 7 needs the answer because it does not pass its business key - which
   * carries the aggregate's ID - to a called process on its own.
   * <p>
   * The default is <code>false</code>: the core answers this, and a test double of
   * this SPI should not invent an answer which makes an adapter change a model.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param otherBpmnProcessId The BPMN process ID to compare with
   * @return Whether both processes serve the same workflow aggregate;
   *         <code>false</code> if either of them is unknown
   */
  default boolean workflowsShareTheWorkflowAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String otherBpmnProcessId) {

    return false;

  }

  /**
   * Whether the <code>&#64;WorkflowTask</code> method serving the given task
   * definition (or BPMN activity ID) completes its task ASYNCHRONOUSLY, which a
   * method says by declaring a <code>&#64;TaskId</code> parameter: the task stays
   * open until the application completes it.
   * <p>
   * Adapters ask this while wiring, because a BPMN element which cannot stay open
   * is a modelling defect the developer should learn about while the application
   * starts rather than as an incident on a live workflow. Camunda 7's
   * <code>camunda:expression</code> is such an element - it completes the task as
   * soon as the expression returns.
   * <p>
   * ONE such method is enough for the answer to be <code>true</code>: several
   * methods may serve one element (different process versions), and an
   * element which cannot stay open is wired wrongly as soon as any of them wants to
   * keep it open.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinitionOrActivityId The task definition or BPMN activity ID
   * @return Whether a matching method completes its task asynchronously;
   *         <code>false</code> if no method is registered at all
   */
  default boolean workflowTaskCompletesAsynchronously(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    // the core answers this; the default keeps test doubles of this SPI compiling
    // and switches such a check off rather than inventing an answer
    return false;

  }

  /**
   * The name of the workflow aggregate's ID property for the given BPMN process -
   * used by remote BPMS without a business-key concept: they store the aggregate's
   * ID as a process variable named after the ID property (the start commands write
   * it, the task workers read it back).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The ID property's name
   * @throws IllegalStateException If the BPMN process is not served by any
   *           workflow service (guiding message)
   */
  String resolveWorkflowAggregateIdName(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Whether a <code>&#64;WorkflowService</code> class of the application claims the given
   * BPMN process. A claimed process is one the application stands in for: its tasks are
   * asked for methods, and a task nothing serves ends the boot. A process nobody claims was
   * deployed only because it shares a file with a claimed one, and nothing of VanillaBP
   * touches it beyond that (see the type).
   * <p>
   * The default answers by {@link #resolveWorkflowAggregateIdName(String, String)}: only a
   * claimed process has a workflow aggregate, so a name means claimed and the exception means
   * not claimed. The core answers from its registry instead, because the name is asked of the
   * application's persistence, and an application on a BPMS with a business key need not
   * answer it. Adapters and extensions ask this instead of catching the exception themselves. An extension gets this interface the way an adapter does, as a bean
   * on Spring Boot and on Quarkus, and may ask it from the moment the workflow services are
   * registered, which is before the first <code>wireBpmn</code>.
   * <p>
   * A process called by a call activity counts as claimed when a workflow service declares it,
   * as the <code>secondaryBpmnProcesses</code> of the workflow service of the caller for
   * example.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID, as the application declares it
   * @return Whether a workflow service claims the process
   */
  default boolean isClaimedByAWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId) {

    try {
      return resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId) != null;
    } catch (final IllegalStateException e) {
      return false;
    }

  }

  /**
   * Hands over what the BPMS knows about the deployed versions of a BPMN process,
   * called during <code>wireBpmn</code> by an adapter whose BPMS can tell. It serves
   * the <code>version</code> attribute of ALL annotations carrying one
   * (<code>&#64;WorkflowTask</code>, <code>&#64;WorkflowStartedByBpms</code>,
   * <code>&#64;WorkflowEnded</code>) and is needed only for specifications naming a
   * version TAG - specifications made of numbers are compared to the version the
   * adapter reports in its invocation contexts, without asking anybody.
   *
   * @param adapterId The adapter ID (the catalog answers for THIS BPMS)
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param catalog The versions of that process
   */
  default void registerProcessVersions(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog catalog) {

  }

  /**
   * Says that this BPMS keeps no catalog of the deployed versions of that BPMN process,
   * called during <code>wireBpmn</code> in the place
   * {@link #registerProcessVersions(String, String, String, io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog)}
   * would be called in.
   * <p>
   * Registering nothing and saying this are two different statements. An adapter which
   * registers nothing may simply not have been asked yet, so the core keeps quiet about
   * the methods of that process. An adapter which says this has answered: there is no
   * version to be had here, and a method waiting for one waits forever. The core then
   * names those methods while the application boots, which is the only moment somebody
   * can still do something about them.
   * <p>
   * What such a delivery DOES carry is the second half of the answer, and it decides
   * which methods are named: a version tag lets a method naming exactly that tag run,
   * and nothing at all leaves only the methods naming no version.
   * <p>
   * Say it for every BPMN process you wire. The core says nothing where a second adapter
   * serves the same process with a catalog, because the method runs on that BPMS then.
   *
   * @param adapterId The adapter ID (the statement is about THIS BPMS)
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param reported What a delivery of this BPMS carries as its process version
   */
  default void reportNoProcessVersionCatalog(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion reported) {

  }

  /**
   * Resolves the version tags the annotations of the given workflow module name, using
   * the catalogs registered by {@link #registerProcessVersions}. Called by the CORE
   * once per workflow module, after the module finished deploying and after the
   * catalogs of the ids nothing was deployed under arrived
   * ({@link #registerVersionsOfProcessesNobodyDeployed}) - so the version deployed by
   * this very boot AND every version tag of a renamed process' old id are part of the
   * answer, and version specifications naming a tag are ambiguous or unknown at
   * STARTUP instead of at the first task delivery. An adapter must NOT call this: an
   * adapter calling it at the end of its own <code>deployResources</code> resolves the
   * tags of the declared-only ids against nothing, because those catalogs arrive
   * later.
   *
   * @param workflowModuleId The workflow module ID
   * @throws IllegalStateException If two methods turn out to serve the same BPMN
   *           element in overlapping version ranges (guiding message)
   */
  default void resolveProcessVersions(
      final String workflowModuleId) {

  }

  /**
   * Registers what one BPMS holds for the BPMN processes of a workflow module which the
   * application DECLARES but deployed nothing under - what a renamed BPMN process leaves
   * behind, where the old id lives on in the BPMS with the workflows still running on it.
   * <p>
   * Called by the core once per adapter of a workflow module, right after the module
   * finished deploying: only the core knows which ids an application declared, and only
   * the adapter can ask its BPMS about them, which is what
   * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService#processVersionCatalogOf}
   * is handed in here for. An adapter must NOT call this.
   * <p>
   * Which ids those are is the core's decision, and it asks about fewer than it could: an
   * id is asked about where the workflow service declaring it ALSO serves a BPMN process
   * this boot deployed. A workflow service whose processes were none of them deployed is
   * waiting for a model which has not arrived yet, which says nothing about a rename.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID (the catalogs answer for THIS BPMS)
   * @param catalogOfProcess Answers what that BPMS holds for one (workflow module, plain
   *          BPMN process ID), or <code>null</code> where it cannot say
   */
  default void registerVersionsOfProcessesNobodyDeployed(
      final String workflowModuleId,
      final String adapterId,
      final java.util.function.BiFunction<String, String, io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog> catalogOfProcess) {

  }

  /**
   * What the <code>&#64;WorkflowTask</code> methods of a workflow module serve for the BPMN
   * processes it DECLARES without bringing a model for them, in the form an adapter composes
   * its BPMS' own identifiers from. An id nothing was deployed under is what renaming a BPMN
   * process leaves behind: the old name lives on in the BPMS with the workflows still
   * running on it, while the resources of the application carry the new one only.
   * <p>
   * An adapter asks this so that those workflows keep being served. The BPMS holds their
   * models, so it knows the tasks it will hand out for them, but the identifier it hands them
   * out under may carry the process id ({@link
   * io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX} scopes a Camunda 8
   * job type by the process it was deployed with) - and then the subscriptions of the
   * deployed processes reach none of them. Whether that matters, and what to do about it, is
   * the adapter's to decide: compose a subscription per entry of this answer, read the models
   * the BPMS still holds under the id, or do nothing at all where such a workflow is served
   * anyway.
   * <p>
   * Which ids are named is the same question
   * {@link #registerVersionsOfProcessesNobodyDeployed} answers, and the same answer: an id
   * is named where the workflow service declaring it ALSO serves a BPMN process this boot
   * deployed. A workflow service whose processes were none of them deployed is waiting for
   * a model which has not arrived yet, which says nothing about a rename.
   * <p>
   * <b>What an entry holds</b> is what the application named its wiring by, plain and
   * unscoped, so an adapter scopes it the way it scopes the wiring of a model it deployed.
   * Today that is the <code>taskDefinition</code> of a method
   * (<code>&#64;WorkflowTask(taskDefinition = ...)</code>, which defaults to the method's
   * name), and a method naming a BPMN element id instead
   * (<code>&#64;WorkflowTask(id = ...)</code>) contributes nothing: an element is matched
   * through the model, and the model of that id is the one thing the application does not
   * have. So an id can be named with an EMPTY collection, and an adapter which composes its
   * identifiers from task definitions can say that those workflows are the ones it will not
   * reach.
   * <p>
   * Read an entry as "what to compose from" rather than as an identifier of your BPMS. What
   * an application may name its wiring by belongs to the surface of
   * <code>spi-for-java</code> and can widen, while what an adapter needs from the core does
   * not change, which is why this method is named after the question rather than after
   * today's answer - see decision 34 in the repository's DECISIONS.md. An adapter must
   * therefore not assume that a value is spelled the way its own BPMS spells one.
   * <p>
   * Answered after the workflow module finished deploying, which is when the difference
   * between declared and deployed is settled. Asking earlier answers less, never
   * something wrong. The default answers nothing, which switches the whole thing off
   * rather than making a test double of this SPI invent declarations.
   *
   * @param workflowModuleId The workflow module ID
   * @return What the methods serve, per plain BPMN process ID, both sorted; empty where the
   *         module declares no id it deployed nothing under
   */
  default java.util.Map<String, Collection<String>> taskWiringOfProcessesNobodyDeployed(
      final String workflowModuleId) {

    return java.util.Map.of();

  }

  /**
   * The version the BPMS assigned to the model THIS boot deployed, reported by the
   * adapter right after its deployment. It tells the core two things it
   * cannot know otherwise: which versions of that process are OLDER, so the startup
   * check knows what to look at, and which version must never be covered by
   * <code>outfaded-versions</code> - fading out the version the application just
   * deployed is a configuration error, and the boot says so.
   * <p>
   * An adapter whose BPMS counts no versions reports nothing, which switches both off.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS assigned, or <code>null</code>
   */
  default void registerDeployedVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

  }
}
