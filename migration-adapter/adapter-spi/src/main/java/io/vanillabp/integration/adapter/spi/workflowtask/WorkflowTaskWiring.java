package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.Collection;
import java.util.List;

/**
 * What a BPMS adapter calls in the core of VanillaBP WHILE IT DEPLOYS. The core (the
 * migration adapter) implements it, and the platform integration hands it to adapters.
 * At runtime the counterpart is {@link WorkflowTaskInvoker}, which the worker threads of
 * the adapter hold. The two were one interface of thirty methods. Then it became clear
 * that the next adapter WILL forget a mandatory call which an adapter can forget. Camunda
 * 7 forgot {@link #validateNoUnwiredWorkflowTaskMethods(String)} for a year, and a typo in
 * a task definition stayed silent until a workflow reached the task.
 *
 * <h2>What is due when</h2>
 *
 * Per BPMN process, while <code>wireBpmn</code> runs. Only the adapter can read its own
 * BPMN dialect, so everything the core needs to know about a model arrives here:
 * <ul>
 * <li>{@link #validateTaskWiring(String, String, String, Collection)}: every BPMN task has
 * a <code>&#64;WorkflowTask</code> method or is marked as served elsewhere. If
 * <code>wireBpmn</code> throws, the <code>deployment-failure</code> policy applies;</li>
 * <li>{@link #taskParameterNames(String, String, String)}: if your BPMS sends variables
 * with a delivery, you have to know their names BEFORE you subscribe;</li>
 * <li>{@link #extensionTaskParameterNames(String, String, List)}: the same for the
 * methods of the extensions, if your BPMS hands a task to one channel only;</li>
 * <li>{@link #multiInstanceElementNames(String, String, String)}: of which iterations a
 * handler wants the item. So a model which hands over no item is refused while it is
 * deployed;</li>
 * <li>{@link #workflowTaskCompletesAsynchronously(String, String, String)}: refuse a
 * wiring which cannot keep a task open, and learn what the model has to be rewritten
 * for;</li>
 * <li>{@link #workflowsShareTheWorkflowAggregate(String, String, String)}: also what the
 * model has to be rewritten for;</li>
 * <li>{@link #reportConcurrentTokenElements(String, String, Collection)}: the elements
 * which can put a second token into a workflow;</li>
 * <li>{@link #reportCompensation(String, String, Collection)}: the compensation throw
 * events which start more than one handler. That is the same second token, in another
 * shape;</li>
 * <li>{@link #reportModelExpressions(String, String, Collection)}: the expressions with
 * which the model reads the data of the workflow;</li>
 * <li>{@link #registerProcessVersions(String, String, String, ProcessVersionCatalog)}:
 * only if your BPMS can place version tags;</li>
 * <li>{@link #reportNoProcessVersionCatalog(String, String, String, io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion)} -
 * if it cannot, and there is nothing to place them in;</li>
 * <li>{@link #unsharedWorkflowAggregateProperties(String, String, Collection, io.vanillabp.integration.adapter.spi.AggregateSyncMode)} -
 * what a model reads, but the aggregate does not share.</li>
 * </ul>
 * At the end of <code>deployResources</code>, per BPMN process:
 * {@link #registerDeployedVersion(String, String, String, String)}. Call it also when your
 * BPMS deployed nothing, because nothing changed. Only the adapter knows which version its
 * BPMS ended up with, so this one stays here.
 * <p>
 * What an adapter may ASK at any point of the pipeline, instead of having to report it:
 * {@link #taskWiringOfProcessesNobodyDeployed(String)}. It says what the methods of the
 * application serve for a BPMN process which the application declares without a model. A
 * renamed BPMN process leaves exactly that behind.
 * <p>
 * <b>What the core does on its own</b>, once the last adapter of a workflow module
 * finished deploying: {@link #validateNoUnwiredWorkflowTaskMethods(String)},
 * {@link #registerVersionsOfProcessesNobodyDeployed(String, String, java.util.function.BiFunction)},
 * {@link #resolveProcessVersions(String)}, {@link #reportExtensionHandlerWiring(String)},
 * and {@link #reportWhatACancelationCannotCarry(String)}. All of them work on the whole
 * module. They compare what the application declared with what the adapters wired. So the
 * core knows when to run them and does it itself. An adapter must NOT call them.
 * <p>
 * <b>A process nobody claims</b> is one which no <code>&#64;WorkflowService</code> class
 * declares ({@link #isClaimedByAWorkflowService(String, String)} answers <code>false</code>).
 * It is deployed only because it shares a file with a claimed one. An adapter leaves it
 * alone: no change of its model beyond what the whole file needs, no worker, no listener, no
 * subscription, and no check of its own which stops the start because of it. The core stops
 * the start because of such a process before anything is deployed. The exception is a
 * process which the application marked as somebody else's with
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.implemented-externally=true</code>.
 * A process which is not in a deployed file at all is not VanillaBP's business either.
 * <p>
 * <b>The BPMN process of a task</b> is the process the task belongs to, wherever a method
 * here asks for one. For a task of a called process (a call activity), that is the called
 * process. It is not the process at the top, which the workflow aggregate was started
 * with. The core binds methods per workflow service class and per process the class
 * declares. That includes the called one, through <code>secondaryBpmnProcesses</code>, and
 * each declaration has a version range of its own. A class serves every process it
 * declares. So the id of the process at the top may find the same method. But then the core
 * applies the range declared for the process at the top, and that is the wrong one for a
 * task of the called process.
 */
public interface WorkflowTaskWiring {

  /**
   * Checks that every given BPMN task is served by a <code>&#64;WorkflowTask</code> method
   * of the workflow services of the process. Or it is marked as served by something else,
   * with <code>implemented-externally=true</code> (see {@link ImplementedExternally}). A user
   * task is no exception: version 1 asked for a method for it as well. A line above the task,
   * at the workflow or higher, covers only the tasks without a method. A line at the task
   * itself, next to a method, is a contradiction. All tasks which are neither served nor
   * marked are collected, and so are all which are served and marked at the task. They are
   * reported in ONE exception, with messages which say what to do. Also, every method which
   * matched is marked as wired. That is the input for
   * {@link #validateNoUnwiredWorkflowTaskMethods(String)}.
   * <p>
   * Call this for the claimed processes only. A process which NO workflow service claims is
   * not checked. A BPMN file goes to the BPMS as a whole. So a process drawn next to the one
   * the application asked for is deployed with it. If VanillaBP demanded methods for a model
   * somebody else owns, it would stop the application over a file the application cannot
   * change. The comment of the type says what happens to such a process.
   * <p>
   * With the adapter id, a task can be marked for one adapter only. The reason is the two
   * adapters of a migration.
   *
   * @param adapterId The id of the adapter deploying the model
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param tasks The tasks of the executable BPMN process to be wired
   * @throws IllegalStateException If a BPMN task of a CLAIMED process has no matching
   *           method and is not marked as served elsewhere. Or if it has one and is marked at
   *           the task itself
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
   * does not name itself. Then a task marked for one adapter only does not count as marked.
   * The default of the method with four arguments calls this one. So a test double of this
   * SPI keeps compiling.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param tasks The tasks of the executable BPMN process to be wired
   * @throws IllegalStateException If a BPMN task of a CLAIMED process has no matching
   *           method and is not marked as served elsewhere. Or if it has one and is marked at
   *           the task itself
   */
  void validateTaskWiring(
      String workflowModuleId,
      String bpmnProcessId,
      Collection<BpmnTaskSpec> tasks);

  /**
   * Whether the application says that something other than itself serves this task. It says
   * so with <code>implemented-externally=true</code>, written for the element id or for the
   * task definition. If both are written, the element id wins (see
   * {@link ImplementedExternally}).
   * <p>
   * An adapter asks this if it refuses a shape by itself, before the core sees the task. A
   * Camunda 7 external task is an example. Such a task is not refused once the application
   * says that somebody else serves it. Whatever the answer, the task still goes to
   * {@link #validateTaskWiring(String, String, String, Collection)}, which holds the rule.
   * The default answers <code>false</code>, so a test double of this SPI keeps compiling.
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
   * Reports the elements of a BPMN process which can put a SECOND token into a running
   * workflow. These are a non-interrupting boundary event, a parallel or inclusive gateway
   * which forks into several flows, a parallel multi-instance activity, a non-interrupting
   * event subprocess, and an ad-hoc subprocess. It is called during <code>wireBpmn</code>,
   * because only the adapter can read its BPMN dialect.
   * <p>
   * The ad-hoc subprocess belongs on that list, whatever the model around it says. Which of
   * its activities run is decided while the workflow is already there. So a model which
   * activates one activity today activates two as soon as the data behind that choice
   * changes. A warning which appears only after such a change is worse than one which
   * always appears.
   * <p>
   * The core decides what it means. Concurrent tokens mean two branches which write the
   * same workflow aggregate. An aggregate without a version attribute loses the writes of
   * the branch which commits first, without any error. The core knows the aggregate class,
   * so it warns once per BPMN process and names the elements reported here. That is why
   * this method takes IDs and not a boolean.
   * <p>
   * An adapter whose BPMS cannot be asked about its models reports nothing. Then the check
   * stays silent and does not guess.
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
   * handler. It is called during <code>wireBpmn</code>, next to
   * {@link #reportConcurrentTokenElements(String, String, Collection)}, and for the same
   * reason: only the adapter can read its BPMN dialect.
   * <p>
   * Compensation is the second token in another shape. A throw event which compensates two
   * finished activities starts both handlers. The workflow holds one token per handler, and
   * each handler writes the workflow aggregate. So it belongs on the list of things which an
   * aggregate without a version attribute cannot survive. It is reported separately, because
   * the flat list cannot carry its shape. The developer has to read WHICH throw event starts
   * WHICH handlers, and the throw event alone says nothing.
   * <p>
   * A throw event which starts one handler is left out. Then the compensation runs where
   * every other activity of the model runs, on the one token the workflow already has.
   * <p>
   * Whether the handlers run one after the other or side by side depends on the engine.
   * Camunda 8 hands out both handler jobs at once. Camunda 7 starts them one after the other
   * in the transaction of the throw event, measured on 7.24. But on Camunda 7, too, a handler
   * which WAITS keeps its token while the next one is started. So the report is the same on
   * both.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param compensations The throw events which start several handlers, with those handlers
   */
  default void reportCompensation(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<CompensationSpec> compensations) {

  }

  /**
   * Reports the expressions with which a BPMN process reads the data of the workflow. So the
   * core can say what the expressions cost which are more than the name of a variable. It is
   * called during <code>wireBpmn</code>, ONCE per BPMN process and with ALL of them,
   * including the plain names. The message counts them. A developer who reads that five of
   * seven expressions already name a variable learns how far their model has come.
   * <p>
   * The adapter finds them, because the places and the language belong to the BPMS. The core
   * judges them, because the answer has to be the same on every BPMS a model is deployed to.
   * An expression which walks a path binds the shape of the application's data everywhere.
   * <p>
   * Report what reads the DATA of the workflow: the conditions of sequence flows and of
   * conditional events, timers, the cardinality, the collection and the completion
   * condition of a multi-instance element, a loop condition, the correlation key of a
   * message, the inputs of a decision, an input or output mapping. Leave out what the BPMS
   * resolves by itself: an expression which names a wired task, a delegate class, a form
   * key. An adapter which cannot read its models reports nothing, and nobody reads anything
   * into that silence.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param expressions The expressions of that process, each with the element it is in and
   *          the place inside it
   */
  default void reportModelExpressions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<io.vanillabp.integration.adapter.spi.expressions.ModelExpression> expressions) {

  }

  /**
   * Checks, after ALL BPMN processes of a workflow module were wired, that every
   * <code>&#64;WorkflowTask</code> method matched a task of at least ONE BPMN process of
   * the module. A workflow service class may declare several processes through
   * {@code secondaryBpmnProcesses}. So a method which matches nothing in one process may
   * rightly serve another. This check covers the other direction, which
   * {@link #validateTaskWiring} cannot decide per process. The core calls it once the last
   * adapter of the workflow module finished deploying. An adapter must not call it (see the
   * type).
   *
   * @param workflowModuleId The workflow module ID
   * @throws IllegalStateException Names every method which matches no task of any
   *           wired BPMN process, with the fix
   */
  void validateNoUnwiredWorkflowTaskMethods(
      String workflowModuleId);

  /**
   * Names the <code>&#64;WorkflowTask</code> methods which would miss values when VanillaBP
   * works out a cancellation by itself. So a developer reads it at startup, not in
   * production.
   * <p>
   * A cancellation which VanillaBP derives carries no job. The element is gone by the time
   * anybody notices. So
   * {@link TaskInvocationContext#getTaskParameter(String)} and
   * {@link TaskInvocationContext#getMultiInstances()} have nothing to answer from. A method
   * which binds neither is not affected and is not named. The CORE calls it once the module
   * is deployed. An adapter must not call it.
   * <p>
   * The default does nothing, so a test double of this SPI keeps compiling.
   *
   * @param workflowModuleId The workflow module which finished deploying
   */
  default void reportWhatACancelationCannotCarry(
      final String workflowModuleId) {

  }

  /**
   * Writes what the handler methods of the extensions were wired to in this workflow
   * module, in the same way as the <code>&#64;WorkflowTask</code> side of a module is
   * reported. The CORE calls it once the module is deployed. An adapter must not call it.
   * <p>
   * The default does nothing, so a test double of this SPI keeps compiling.
   *
   * @param workflowModuleId The workflow module ID
   */
  default void reportExtensionHandlerWiring(
      final String workflowModuleId) {

    // the core answers this

  }

  /**
   * Which of the given names are attributes of the workflow aggregate which are NOT shared
   * with the BPMS. The startup check for such expressions asks this.
   * <p>
   * An embedded engine can read the BPMN model, and only the core knows what an aggregate
   * shares. So the adapter collects the identifiers its models read (a condition, a timer,
   * a multi-instance collection). It asks here which of them would always be
   * <code>null</code> in the engine, although the application clearly meant an attribute of
   * its aggregate. A name which is no attribute at all does not concern this check. It may
   * well be a variable which the model itself provides.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param names The identifiers read by the model
   * @param adapterDefault What this adapter shares unless the application says
   *          otherwise
   * @return The names which are attributes of the aggregate but not shared, in the
   *         given order. Empty if the BPMN process is unknown
   * @see #unsharedWorkflowAggregatePaths(String, String, java.util.Collection,
   *      io.vanillabp.integration.adapter.spi.AggregateSyncMode) for an adapter which can
   *      read a whole path from its model. That is the more complete question
   */
  default java.util.Collection<String> unsharedWorkflowAggregateProperties(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> names,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    return java.util.List.of();

  }

  /**
   * The same question for a PATH which an expression reads
   * (<code>order.customer.address.city</code>): which of the given paths stop before they
   * reach a value the BPMS holds, and where.
   * <p>
   * An adapter which can read the whole path from its model asks this instead of
   * {@link #unsharedWorkflowAggregateProperties}, which only answers about the first
   * segment. The nested case is the one worth asking about. The shared values are a
   * structure. So an expression which navigates into them meets the sync model at every
   * segment. A segment two levels down which is not shared reads <code>null</code>, just
   * like one at the top level.
   * <p>
   * A path whose FIRST segment is no attribute of the aggregate at all is NOT reported.
   * The reason is the same as for the question about single names: the model may well
   * provide a variable of that name. Everything below the first segment is reported,
   * because there the expression navigates the aggregate.
   * <p>
   * The core answers from the DECLARED types of the segments. Where they cannot decide, it
   * stays silent. So a path which is missing from the answer means either "this works" or
   * "this cannot be judged". It never means "this was checked and found broken".
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param paths The paths read by the model, segments separated by dots
   * @param adapterDefault What this adapter shares unless the application says
   *          otherwise
   * @return The paths to report, with what the walk found, keyed by the path as it was
   *         given. Empty if the BPMN process is unknown
   */
  default java.util.Map<String, io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict> unsharedWorkflowAggregatePaths(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> paths,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    return java.util.Map.of();

  }

  /**
   * At which type each of the given paths ends. So an adapter can judge what its BPMS makes
   * of the value.
   * <p>
   * A BPMS stores what VanillaBP hands it. The format it stores it in may give something
   * else back. Whether that matters is up to the adapter, because only the adapter knows
   * which types its BPMS carries unchanged. The core knows the types and answers, one object
   * per deployed process, the way it answers every other question about a model.
   * <p>
   * A path is answered only if it reaches a value the BPMS holds. A path which the sync model
   * cuts short is missing here, because such a value never leaves the application. A path
   * which the declared types cannot decide is missing too. That is the same silence as in
   * {@link #unsharedWorkflowAggregatePaths}. Both questions use the same walk, so a path
   * reported there is never answered here.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param paths The paths read by the model, segments separated by dots
   * @param adapterDefault What this adapter shares unless the application says
   *          otherwise
   * @return The declared type per path, keyed by the path as it was given. It holds only
   *         the paths which reach a value. Empty if the BPMN process is unknown
   */
  default java.util.Map<String, Class<?>> declaredTypesOfWorkflowAggregatePaths(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> paths,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    return java.util.Map.of();

  }

  /**
   * The process variables which the <code>&#64;WorkflowTask</code> methods for the given
   * task definition (or BPMN activity ID) read with <code>&#64;TaskParam</code>. The names
   * are spelled as in the annotation.
   * <p>
   * With a <code>&#64;TaskParam</code> the application reads what the BPMS GENERATED on
   * this path: a value from an input or output mapping, the result of a script or a
   * decision, something the model computed and the workflow aggregate does not hold. A BPMS
   * which hands its worker variables has to know these names. Then it fetches only what is
   * really read. These names exist only in the core. The adapter sees a
   * {@link TaskInvocationContext#getTaskParameter(String)} call one name at a time, and only
   * once the delivery is already there.
   * <p>
   * Several methods may serve one element (different process versions). So the answer is
   * the UNION of their parameters: the delivery has to satisfy whichever of them runs. The
   * names are sorted and without duplicates. A subscription which compares itself across
   * restarts needs that (a Camunda 8 job stream equals another one only if the fetched
   * variables match).
   * <p>
   * The default answers nothing. That switches the derivation off, instead of making an
   * adapter fetch an incomplete list.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process the task belongs to - for a task of a called
   *          process the called process, not the one at the top
   * @param taskDefinitionOrActivityId The task definition or BPMN activity ID
   * @return The declared parameter names, sorted. Empty if no method is registered, or
   *         if none of them declares a <code>&#64;TaskParam</code>
   */
  default Collection<String> taskParameterNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    return java.util.List.of();

  }

  /**
   * The process variables which the methods of the EXTENSIONS read with
   * <code>&#64;TaskParam</code> for one element, over every extension which registered a
   * handler contract. The names are spelled as in the annotation. This is the counterpart
   * of {@link #taskParameterNames(String, String, String)}, which answers only for the
   * <code>&#64;WorkflowTask</code> methods.
   * <p>
   * It is meant for a BPMS which hands a task to exactly ONE channel. The
   * Process-Engine-API is one. It delivers a task to one subscription. So an extension gets
   * no channel of its own, and it reads what the adapter's subscription delivered. That
   * subscription also has to ask for what the extensions read. The adapter cannot ask each
   * extension, because it does not know their annotations. Some BPMS let an extension open a
   * channel of its own (a Camunda 7 listener, a Camunda 8 job worker). They do not need
   * this, because there the extension asks for its own variables.
   * <p>
   * Pass the keys in the order in which the extensions look up an element, most wanted
   * first: the BPMN element id, then the task definition. The methods of each extension are
   * found by walking those keys. The answer is the UNION over every method which may run for
   * this element in some version. It may name one variable too many, but it never misses
   * one. The names are sorted and without duplicates, so a subscription built from them
   * stays the same across restarts. Ask this together with {@link #taskParameterNames}, not
   * instead of it, and fetch the union of both answers.
   * <p>
   * The answer covers the contracts registered and the workflow services scanned at the
   * moment it is asked. So ask it as late as you can: when you open the channel, not
   * earlier. The default answers nothing, so a test double compiled against an older core
   * stays unchanged.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process the task belongs to - for a task of a called
   *          process the called process, not the one at the top
   * @param lookupKeys The keys an extension method may be matched by, most wanted first
   * @return The parameter names, sorted. Empty if no extension registered a contract, or
   *         if none of their methods for these keys declares a <code>&#64;TaskParam</code>
   */
  default Collection<String> extensionTaskParameterNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys) {

    return List.of();

  }

  /**
   * The BPMN elements of which the <code>&#64;WorkflowTask</code> methods for the given
   * task definition (or BPMN activity ID) want the ITEM of an iteration. These are the ids
   * <code>&#64;MultiInstanceElement</code> names. If a resolver bean answers instead, they
   * are the ids
   * {@link io.vanillabp.spi.service.MultiInstanceElementResolver#getNames()} reports.
   * <p>
   * A multi-instance element hands several things to a handler. The item is the one part a
   * model may not carry at all. A Camunda 7 element without
   * <code>camunda:elementVariable</code> and a Camunda 8 one without
   * <code>inputElement</code> iterate without ever naming the value of an iteration. Such a
   * model is fine, and so is a handler which reads only the index and the total. But the
   * two together are not fine: the parameter receives <code>null</code>, and nothing says
   * why.
   * <p>
   * Neither side sees both halves. Only the adapter reads the BPMN, and only the core scans
   * the handlers. So an adapter asks this while it deploys, and it refuses a pair which its
   * model cannot serve. The index and the total need no such question. That is why this
   * method is only about the item.
   * <p>
   * An id which is no element of the model being deployed is NOT a problem. The
   * multi-instance chain crosses a call activity. So a task of a called process asks for an
   * element of its caller, and the model at hand is the wrong place to look for it.
   * <p>
   * Several methods may serve one element (different process versions). So the answer is
   * the UNION of what they ask for: whichever of them runs, they read their item from the
   * same model. The ids are sorted and without duplicates.
   * <p>
   * The default answers nothing. That switches such a check off, instead of letting an
   * adapter judge a model against half the question.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinitionOrActivityId The task definition or BPMN activity ID
   * @return The BPMN element ids an item is asked for, sorted. Empty if no method is
   *         registered, or if none of them declares a <code>&#64;MultiInstanceElement</code>
   */
  default Collection<String> multiInstanceElementNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    return java.util.List.of();

  }

  /**
   * Whether two BPMN processes of one workflow module work on the SAME workflow aggregate.
   * The declaration says so: one class declares the process to be started as its
   * {@code bpmnProcess}, and the others as {@code secondaryBpmnProcesses}.
   * <p>
   * Adapters ask this about a call activity. A process called on the same aggregate
   * continues the same business case and has to reach the same aggregate. But a process
   * with an aggregate of its own must not get the identity of the caller. Camunda 7 needs
   * the answer, because it does not pass its business key to a called process by itself.
   * The business key carries the ID of the aggregate.
   * <p>
   * The default is <code>false</code>. The core answers this, and a test double of this
   * SPI should not make up an answer which makes an adapter change a model.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param otherBpmnProcessId The BPMN process ID to compare with
   * @return Whether both processes serve the same workflow aggregate.
   *         <code>false</code> if either of them is unknown
   */
  default boolean workflowsShareTheWorkflowAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String otherBpmnProcessId) {

    return false;

  }

  /**
   * Whether the <code>&#64;WorkflowTask</code> method for the given task definition (or
   * BPMN activity ID) completes its task ASYNCHRONOUSLY. A method says so by declaring a
   * <code>&#64;TaskId</code> parameter. The task then stays open until the application
   * completes it.
   * <p>
   * Adapters ask this while they wire. A BPMN element which cannot stay open is a
   * modelling defect. The developer should learn about it while the application starts,
   * not as an incident on a live workflow. The <code>camunda:expression</code> of Camunda 7
   * is such an element. It completes the task as soon as the expression returns.
   * <p>
   * ONE such method is enough for the answer to be <code>true</code>. Several methods may
   * serve one element (different process versions). An element which cannot stay open is
   * wired wrongly as soon as any of them wants to keep it open.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinitionOrActivityId The task definition or BPMN activity ID
   * @return Whether a matching method completes its task asynchronously.
   *         <code>false</code> if no method is registered at all
   */
  default boolean workflowTaskCompletesAsynchronously(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    // the core answers this. The default keeps test doubles of this SPI compiling,
    // and it switches such a check off instead of making up an answer
    return false;

  }

  /**
   * The name of the ID property of the workflow aggregate for the given BPMN process.
   * Remote BPMS without a business key use it. They store the ID of the aggregate as a
   * process variable named after the ID property. The start commands write it, and the
   * task workers read it back.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The ID property's name
   * @throws IllegalStateException If no workflow service serves the BPMN process
   *           (with a message which says what to do)
   */
  String resolveWorkflowAggregateIdName(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Whether a <code>&#64;WorkflowService</code> class of the application claims the given
   * BPMN process. The application is responsible for a claimed process. Its tasks need
   * methods, and a task which nothing serves stops the start. A process nobody claims was
   * deployed only because it shares a file with a claimed one. Apart from that, VanillaBP
   * does not touch it (see the type).
   * <p>
   * The default answers with {@link #resolveWorkflowAggregateIdName(String, String)}. Only a
   * claimed process has a workflow aggregate. So a name means claimed, and the exception
   * means not claimed. The persistence of a claimed process may have no name to give. Its
   * <code>getAggregateIdName</code> then throws an <code>UnsupportedOperationException</code>,
   * which an application on a BPMS with a business key is allowed to do. The default answers
   * <code>true</code> for that exception, because only a claimed process gets as far as its
   * persistence. The core answers from its registry instead. The reason: the name
   * comes from the persistence of the application, and an application on a BPMS with a
   * business key does not have to answer it. Adapters and extensions ask this, instead of
   * catching the exception themselves. An extension gets this interface the way an adapter
   * does, as a bean on Spring Boot and on Quarkus. It may ask from the moment the workflow
   * services are registered, which is before the first <code>wireBpmn</code>.
   * <p>
   * A process called by a call activity counts as claimed if a workflow service declares
   * it, for example as a <code>secondaryBpmnProcesses</code> entry of the workflow service
   * of the caller.
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
    } catch (final UnsupportedOperationException e) {
      return true;
    }

  }

  /**
   * Hands over what the BPMS knows about the deployed versions of a BPMN process. An adapter
   * whose BPMS can tell calls it during <code>wireBpmn</code>. It serves the
   * <code>version</code> attribute of ALL annotations which have one
   * (<code>&#64;WorkflowTask</code>, <code>&#64;WorkflowStartedByBpms</code>,
   * <code>&#64;WorkflowEnded</code>). It is needed only for ranges which name a version TAG.
   * Ranges made of numbers are compared with the version the adapter reports in its
   * invocation contexts, without asking anybody.
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
   * Says that this BPMS keeps no catalog of the deployed versions of that BPMN process. It
   * is called during <code>wireBpmn</code>, in the place where
   * {@link #registerProcessVersions(String, String, String, io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog)}
   * would be called.
   * <p>
   * Registering nothing and saying this are two different statements. An adapter which
   * registers nothing may just not have been asked yet. So the core says nothing about the
   * methods of that process. An adapter which says this has answered: there is no version
   * to get here, and a method which waits for one waits forever. The core then names those
   * methods while the application starts. That is the only moment when somebody can still
   * do something about them.
   * <p>
   * What such a delivery DOES carry is the second half of the answer. It decides which
   * methods are named. A version tag lets a method run which names exactly that tag. If a
   * delivery carries nothing at all, only the methods which name no version are left.
   * <p>
   * Say it for every BPMN process you wire. The core says nothing if a second adapter
   * serves the same process with a catalog, because then the method runs on that BPMS.
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
   * Resolves the version tags which the annotations of the given workflow module name. It
   * uses the catalogs registered by {@link #registerProcessVersions}. The CORE calls it once
   * per workflow module. It does so after the module finished deploying, and after the
   * catalogs of the ids without a deployment arrived
   * ({@link #registerVersionsOfProcessesNobodyDeployed}). So the answer includes the version
   * deployed by this very start AND every version tag of the old id of a renamed process.
   * Then a range which names an unclear or unknown tag shows up at STARTUP, not at the first
   * task delivery. An adapter must NOT call this. If an adapter called it at the end of its
   * own <code>deployResources</code>, it would resolve the tags of the ids which are only
   * declared against nothing, because those catalogs arrive later.
   *
   * @param workflowModuleId The workflow module ID
   * @throws IllegalStateException If two methods serve the same BPMN element in
   *           overlapping version ranges (with a message which says what to do)
   */
  default void resolveProcessVersions(
      final String workflowModuleId) {

  }

  /**
   * Registers what one BPMS holds for the BPMN processes of a workflow module which the
   * application DECLARES, but deployed nothing under. A renamed BPMN process leaves this
   * behind: the old id lives on in the BPMS, with the workflows still running on it.
   * <p>
   * The core calls it once per adapter of a workflow module, right after the module
   * finished deploying. Only the core knows which ids an application declared, and only the
   * adapter can ask its BPMS about them. That is why
   * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService#processVersionCatalogOf}
   * is passed in here. An adapter must NOT call this.
   * <p>
   * The core decides which ids those are, and it asks about fewer than it could. It asks
   * about an id only if the workflow service which declares it ALSO serves a BPMN process
   * this start deployed. If none of the processes of a workflow service were deployed, it
   * waits for a model which has not arrived yet. That says nothing about a rename.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID (the catalogs answer for THIS BPMS)
   * @param catalogOfProcess Answers what that BPMS holds for one (workflow module, plain
   *          BPMN process ID), or <code>null</code> if it cannot say
   */
  default void registerVersionsOfProcessesNobodyDeployed(
      final String workflowModuleId,
      final String adapterId,
      final java.util.function.BiFunction<String, String, io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog> catalogOfProcess) {

  }

  /**
   * What the <code>&#64;WorkflowTask</code> methods of a workflow module serve for the BPMN
   * processes which the module DECLARES without a model. The answer has the form from which
   * an adapter builds the own identifiers of its BPMS. An id without a deployment is what a
   * renamed BPMN process leaves behind. The old name lives on in the BPMS, with the
   * workflows still running on it, while the resources of the application carry only the
   * new one.
   * <p>
   * An adapter asks this so that those workflows keep being served. The BPMS holds their
   * models, so it knows the tasks it will hand out for them. But the identifier under which
   * it hands them out may carry the process id ({@link
   * io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX} scopes a Camunda 8
   * job type by the process it was deployed with). Then the subscriptions of the deployed
   * processes reach none of them. The adapter decides whether that matters and what to do.
   * It can build a subscription per entry of this answer, read the models the BPMS still
   * holds under the id, or do nothing at all if such a workflow is served anyway.
   * <p>
   * Which ids are named is the same question that
   * {@link #registerVersionsOfProcessesNobodyDeployed} answers, with the same answer. An id
   * is named if the workflow service which declares it ALSO serves a BPMN process this start
   * deployed. If none of the processes of a workflow service were deployed, it waits for a
   * model which has not arrived yet. That says nothing about a rename.
   * <p>
   * <b>What an entry holds</b> is the name by which the application wired its methods,
   * plain and without scope. So an adapter scopes it the way it scopes the wiring of a model
   * it deployed. Today that is the <code>taskDefinition</code> of a method
   * (<code>&#64;WorkflowTask(taskDefinition = ...)</code>, which defaults to the name of the
   * method). A method which names a BPMN element id instead
   * (<code>&#64;WorkflowTask(id = ...)</code>) adds nothing. An element is matched through
   * the model, and the model of that id is the one thing the application does not have. So
   * an id can be named with an EMPTY collection. An adapter which builds its identifiers
   * from task definitions can then say that it will not reach those workflows.
   * <p>
   * Read an entry as "what to build from", not as an identifier of your BPMS. What an
   * application may wire its methods by belongs to the surface of <code>spi-for-java</code>
   * and can grow. What an adapter needs from the core does not change. That is why this
   * method is named after the question and not after today's answer. See decision 34 in
   * the repository's DECISIONS.md. So an adapter must not assume that a value is spelled the
   * way its own BPMS spells one.
   * <p>
   * The answer is complete after the workflow module finished deploying, because then the
   * difference between declared and deployed is settled. If you ask earlier, the answer is
   * smaller, but never wrong. The default answers nothing. That switches the whole thing
   * off, instead of making a test double of this SPI make up declarations.
   *
   * @param workflowModuleId The workflow module ID
   * @return What the methods serve, per plain BPMN process ID, both sorted. Empty if the
   *         module declares no id without a deployment
   */
  default java.util.Map<String, Collection<String>> taskWiringOfProcessesNobodyDeployed(
      final String workflowModuleId) {

    return java.util.Map.of();

  }

  /**
   * The version the BPMS assigned to the model which THIS start deployed. The adapter
   * reports it right after its deployment. It tells the core two things it cannot know
   * otherwise. First, which versions of that process are OLDER, so the startup check knows
   * what to look at. Second, which version must never be covered by
   * <code>outfaded-versions</code>. Fading out the version the application just deployed is
   * a configuration error, and the start says so.
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
