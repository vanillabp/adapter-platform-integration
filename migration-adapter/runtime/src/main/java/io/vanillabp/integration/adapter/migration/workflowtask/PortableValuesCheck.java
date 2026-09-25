package io.vanillabp.integration.adapter.migration.workflowtask;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport;
import io.vanillabp.integration.adapter.migration.values.DeclaredAggregateValues;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;
import io.vanillabp.spi.service.WorkflowTasks;

/**
 * Refuses to start an application which hands the BPMS a value the BPMS may not give
 * back the way it was given, and which nobody said anything about.
 *
 * <h2>Why the two directions are judged differently</h2>
 *
 * A value of the workflow aggregate travels so that a model can decide on it, and the
 * thing reading it is the expression language of the BPMS. Only a <code>boolean</code>
 * and a text mean the same in every expression language, so those two travel and the
 * rest is declared first. The wrapper {@link Boolean} is not among them on purpose: a
 * <code>null</code> in a gateway condition means something different on every BPMS, and
 * a value nobody computed must not carry a decision.
 * <p>
 * A value arriving in a <code>&#64;TaskParam</code> parameter is read by the handler and
 * by nobody else, so no expression language is involved and the serialization decides
 * alone. A <code>BigDecimal</code> is fine there as long as an adapter says what its
 * BPMS does with it. The rule is softer in that direction on purpose.
 *
 * <h2>Where the answers come from</h2>
 *
 * The set of places is complete while the application starts: the sync model says which
 * values of the aggregate leave, and the <code>&#64;TaskParam</code> parameters say which
 * come back. What a BPMS does with a type is its adapter's answer
 * ({@link MigratableProcessService#whatThisBpmsDoesWith}), and an adapter is allowed to
 * say that it cannot tell. That answer is a warning and never the end of a startup,
 * because an application whose BPMS is unreachable while it boots still has to boot.
 */
public class PortableValuesCheck {

  private static final Logger log = LoggerFactory.getLogger(PortableValuesCheck.class);

  /**
   * The wiki page carrying the whole rule, named by every message so a developer has one
   * place to read up.
   */
  private static final String DOCUMENTATION = "https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-aggregates";

  private final WorkflowAggregateSync aggregateSync;

  private final MigrationAdapterProperties properties;

  /**
   * Built by the registry which registers the workflows, once per application.
   * <p>
   * Both of these may be missing where a test registers workflow services directly.
   * Nothing is refused then, because neither the values which travel nor the declarations
   * can be read.
   *
   * @param aggregateSync The core's sync model, which answers which values travel
   * @param properties The VanillaBP configuration, which holds the declarations
   */
  public PortableValuesCheck(
      final WorkflowAggregateSync aggregateSync,
      final MigrationAdapterProperties properties) {

    this.aggregateSync = aggregateSync;
    this.properties = properties;

  }

  /**
   * Ends the startup where a value travels whose type the BPMS may not give back
   * unchanged and which nobody declared. Called once per registered workflow, AFTER the
   * sync model was validated: the annotations decide which values leave at all, and
   * without that set the types of the wrong values would be judged.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID being registered
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class declaring it
   * @param workflowAggregateClass The workflow aggregate of that workflow
   * @param aggregateIdAttribute The name of the aggregate's ID attribute, or
   *          <code>null</code> where the persistence does not name one
   * @param adapters The adapters serving this workflow, asked what their BPMS does with
   *          a type
   * @throws IllegalStateException Naming every value, its type, its direction, why it
   *           does not travel well and the two ways on
   */
  public void refuseValuesWhichDoNotTravelWell(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final String aggregateIdAttribute,
      final List<? extends MigratableProcessService<?>> adapters) {

    if (!(aggregateSync instanceof final AggregateSyncSupport syncModel) || (properties == null)) {
      return;
    }
    final var declared = syncModel.getDeclaredValues();
    declared
        .register(
            workflowAggregateClass,
            properties.declaredAggregateValues(workflowModuleId, bpmnProcessId));

    final var defects = new LinkedList<String>();
    judgeAggregateValues(
        workflowAggregateClass,
        aggregateIdAttribute,
        declared,
        adapters,
        defects);
    judgeTaskParams(workflowModuleId, bpmnProcessId, workflowServiceClass, adapters, defects);
    if (defects.isEmpty()) {
      return;
    }

    throw new IllegalStateException(
        """
            BPMN process '%s' of workflow module '%s' moves values between the application and the \
            BPMS which may not arrive as what they were:

            %s

            There are two ways on for each of them. Put the decision into Java, where a boolean \
            getter of the aggregate carries the expression and the model reads nothing but its \
            name. Or declare the value, which says that you looked at it and know what your BPMS \
            makes of it:

              %s:
                - <value>
              %s:
                - <parameter>

            A business rule task is the ordinary reason to declare. A decision table decides about \
            numbers and dates, that is what it is for, and its inputs are variables, so declaring \
            them is the intended way and not a breach of the rule.

            The whole rule is on %s."""
            .formatted(
                bpmnProcessId,
                workflowModuleId,
                String.join("\n", defects),
                MigrationAdapterProperties.declaredAggregateValuesProperty(workflowModuleId, bpmnProcessId),
                MigrationAdapterProperties
                    .declaredTaskParamsProperty(workflowModuleId, bpmnProcessId, "<task>"),
                DOCUMENTATION));

  }

  /**
   * The values of the aggregate on their way to the BPMS: a <code>boolean</code> and a
   * text travel, everything else is declared first.
   */
  private void judgeAggregateValues(
      final Class<?> workflowAggregateClass,
      final String aggregateIdAttribute,
      final DeclaredAggregateValues declared,
      final List<? extends MigratableProcessService<?>> adapters,
      final List<String> defects) {

    final var idAttribute = aggregateIdAttribute != null
        ? aggregateIdAttribute
        : "id";
    // FULL is the default of every adapter, so an aggregate which annotates nothing is
    // walked as a whole - the same starting point the sync model's own validation takes
    for (final var shared : ((AggregateSyncSupport) aggregateSync)
        .valuesSharedWithBpms(workflowAggregateClass, AggregateSyncMode.FULL)) {
      if (shared.path().equals(idAttribute)) {
        // the aggregate's id reaches the BPMS whatever the sync model says, and how it
        // travels is the adapter's business (see WorkflowAggregateSync)
        continue;
      }
      if (travelsInEveryExpressionLanguage(shared.declaredType())) {
        continue;
      }
      if (declared.covers(workflowAggregateClass, shared.path())) {
        continue;
      }
      defects
          .add(
              "  - the aggregate value '%s' is declared as %s on its way TO the BPMS. %s%s"
                  .formatted(
                      shared.path(),
                      nameOf(shared.declaredType()),
                      whyItDoesNotTravel(shared.declaredType()),
                      whatTheAdaptersSay(adapters, shared.declaredType(), ValueDirection.TO_BPMS)));
    }

  }

  /**
   * The values arriving in <code>&#64;TaskParam</code> parameters: here the serialization
   * decides, so an adapter saying that its BPMS hands the type back unchanged is enough.
   */
  private void judgeTaskParams(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final List<? extends MigratableProcessService<?>> adapters,
      final List<String> defects) {

    for (final var method : workflowServiceClass.getMethods()) {
      final var taskId = taskIdOf(method);
      if (taskId == null) {
        continue;
      }
      final var declaredParams = properties.declaredTaskParams(workflowModuleId, bpmnProcessId, taskId);
      for (final var parameter : method.getParameters()) {
        final var taskParam = parameter.getAnnotation(TaskParam.class);
        if (taskParam == null) {
          continue;
        }
        if (declaredParams.contains(taskParam.value())) {
          continue;
        }
        final var defect = judgeOneTaskParam(
            workflowServiceClass,
            method,
            taskParam.value(),
            parameter,
            adapters);
        if (defect != null) {
          defects.add(defect);
        }
      }
    }

  }

  /**
   * @return The defect of that parameter, or <code>null</code> where it is fine
   */
  private String judgeOneTaskParam(
      final Class<?> workflowServiceClass,
      final Method method,
      final String parameterName,
      final Parameter parameter,
      final List<? extends MigratableProcessService<?>> adapters) {

    final var location = "%s#%s".formatted(workflowServiceClass.getName(), method.getName());
    final var type = parameter.getType();
    if (namesNoType(type)) {
      return """
            - the @TaskParam '%s' of '%s' is declared as %s, which names no type at all. Every BPMS \
          answers such a parameter with the type its own serialization produced: Camunda 7 hands a \
          decimal back as a BigDecimal and Camunda 8 as a Double, and the application finds that out \
          while it runs. Declaring it means you take that on and cover it with tests."""
          .formatted(parameterName, location, nameOf(type));
    }
    final var changing = adapters
        .stream()
        .map(adapter -> Map
            .entry(adapter.getAdapterId(), adapter.whatThisBpmsDoesWith(type, ValueDirection.FROM_BPMS)))
        .filter(answer -> answer.getValue().kind() == ValueTypeVerdict.Kind.CHANGED)
        .toList();
    if (changing.isEmpty()) {
      warnAboutAdaptersWhichCannotSay(
          adapters,
          type,
          ValueDirection.FROM_BPMS,
          "the @TaskParam '%s' of '%s'".formatted(parameterName, location));
      return null;
    }
    return """
        - the @TaskParam '%s' of '%s' is declared as %s on its way FROM the BPMS, and it does not \
        arrive as that: %s"""
        .formatted(
            parameterName,
            location,
            nameOf(type),
            changing
                .stream()
                .map(answer -> "adapter '%s' says %s".formatted(answer.getKey(), answer.getValue().explanation()))
                .collect(java.util.stream.Collectors.joining("; ")));

  }

  /**
   * Whether a value of that type means the same in every expression language a BPMS may
   * bring. Two types do: the plain <code>boolean</code> and a text.
   *
   * @param type The declared type
   * @return Whether it travels to the BPMS without being declared
   */
  private static boolean travelsInEveryExpressionLanguage(
      final Class<?> type) {

    return (type == boolean.class) || CharSequence.class.isAssignableFrom(type);

  }

  /**
   * Why that type does not travel to the BPMS on its own. The wrapper
   * {@link Boolean} gets its own sentence, because without it the rule looks like
   * pedantry.
   *
   * @param type The declared type
   * @return The sentence for the message
   */
  private static String whyItDoesNotTravel(
      final Class<?> type) {

    if (type == Boolean.class) {
      return """
          A plain boolean travels, the wrapper does not: a null in a gateway condition means \
          something different on every BPMS, and a value nobody computed must not carry a \
          decision. Declare the attribute as boolean, or give the aggregate a boolean getter \
          which answers what the model really asks.""";
    }
    return """
        Only a boolean and a text mean the same in every expression language, and a value of the \
        aggregate is there to carry a decision.""";

  }

  /**
   * What the adapters of the workflow say about that type, appended to a message about a
   * value which does not travel. An adapter which says nothing new is left out, so the
   * message carries what a reader can act on.
   */
  private String whatTheAdaptersSay(
      final List<? extends MigratableProcessService<?>> adapters,
      final Class<?> type,
      final ValueDirection direction) {

    final var said = adapters
        .stream()
        .map(adapter -> Map.entry(adapter.getAdapterId(), adapter.whatThisBpmsDoesWith(type, direction)))
        .filter(answer -> answer.getValue().kind() == ValueTypeVerdict.Kind.CHANGED)
        .map(answer -> "adapter '%s' says %s".formatted(answer.getKey(), answer.getValue().explanation()))
        .toList();
    return said.isEmpty()
        ? ""
        : " "
            + String.join("; ", said)
            + ".";

  }

  /**
   * Says once, as a warning, which adapters could not judge a type. A startup never ends
   * on that answer, and staying silent about it would leave the developer believing the
   * value was checked.
   */
  private void warnAboutAdaptersWhichCannotSay(
      final List<? extends MigratableProcessService<?>> adapters,
      final Class<?> type,
      final ValueDirection direction,
      final String what) {

    final var silent = adapters
        .stream()
        .map(adapter -> Map.entry(adapter.getAdapterId(), adapter.whatThisBpmsDoesWith(type, direction)))
        .filter(answer -> answer.getValue().kind() == ValueTypeVerdict.Kind.CANNOT_SAY)
        .map(answer -> "'%s' (%s)".formatted(answer.getKey(), answer.getValue().explanation()))
        .toList();
    if (silent.isEmpty()) {
      return;
    }
    log
        .warn(
            "Nobody can say what happens to {}, which is declared as {}: the adapter(s) {} did not judge the "
                + "type. The value is not refused, and it is not checked either - a test against the "
                + "BPMS you run is the only thing which answers this.",
            what,
            nameOf(type),
            String.join(", ", silent));

  }

  /**
   * Whether the declaration names no concrete type, so what arrives is whatever the
   * serialization of the BPMS produced. An {@link Object} is the plain case, a raw or
   * wildcard collection and a {@link Map} are the same thing one level down.
   *
   * @param type The declared type
   * @return Whether the declaration decides nothing
   */
  private static boolean namesNoType(
      final Class<?> type) {

    return (type == Object.class) || Map.class.isAssignableFrom(type) || Collection.class
        .isAssignableFrom(type);

  }

  /**
   * The task the configuration knows that method under, or <code>null</code> where the
   * method is none VanillaBP reads <code>&#64;TaskParam</code> parameters of.
   * <p>
   * A <code>&#64;WorkflowStartedByBpms</code> method has no task around it, so its
   * parameters are declared at the workflow. It is named here anyway, because its
   * parameters come from the BPMS exactly as a task's do.
   */
  private static String taskIdOf(
      final Method method) {

    final var task = method.getAnnotation(WorkflowTask.class);
    if (task != null) {
      return WorkflowTaskScanner.taskConfigurationKeyOf(method, task);
    }
    if (method.getAnnotation(WorkflowTasks.class) != null) {
      // several tasks on one method: whichever of them is configured, the parameter is
      // the same one, so the method's name is the key the message hands out
      return method.getName();
    }
    return method.getAnnotation(WorkflowStartedByBpms.class) != null
        ? method.getName()
        : null;

  }

  /**
   * How a type is written in a message: its canonical name, so a reader knows which class
   * is meant and can search for it.
   *
   * @param type The type
   * @return The name for the message
   */
  private static String nameOf(
      final Class<?> type) {

    final var canonical = type.getCanonicalName();
    return canonical != null
        ? canonical
        : type.getName();

  }

}
