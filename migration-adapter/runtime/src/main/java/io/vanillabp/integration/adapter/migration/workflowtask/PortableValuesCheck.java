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
 * Refuses to start an application which hands the BPMS a value that the BPMS may not give
 * back the way it was given, where nobody said anything about that value.
 *
 * <h2>Why the two directions are judged differently</h2>
 *
 * A value of the workflow aggregate travels so that a model can decide on it. The
 * expression language of the BPMS reads it. Only a <code>boolean</code> and a text mean the
 * same in every expression language. So those two travel, and the rest has to be declared
 * first. The wrapper {@link Boolean} is left out on purpose. A <code>null</code> in a
 * gateway condition means something different on every BPMS, and a value which nobody
 * computed must not decide anything.
 * <p>
 * A value which arrives in a <code>&#64;TaskParam</code> parameter is read by the handler
 * and by nobody else. No expression language is involved, so the serialization alone
 * decides. A <code>BigDecimal</code> is fine there, as long as an adapter says what its BPMS
 * does with it. The rule is softer in that direction on purpose.
 *
 * <h2>Where the answers come from</h2>
 *
 * All the places are known while the application starts. The sync model says which values
 * of the aggregate leave, and the <code>&#64;TaskParam</code> parameters say which come
 * back. What a BPMS does with a type is its adapter's answer
 * ({@link MigratableProcessService#whatThisBpmsDoesWith}). An adapter may say that it cannot
 * tell. That answer is a warning and never ends a startup. An application whose BPMS cannot
 * be reached while it boots still has to boot.
 */
public class PortableValuesCheck {

  private static final Logger log = LoggerFactory.getLogger(PortableValuesCheck.class);

  /**
   * The wiki page with the whole rule. Every message names it, so a developer has one place
   * to read about it.
   */
  private static final String DOCUMENTATION = "https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-aggregates";

  private final WorkflowAggregateSync aggregateSync;

  private final MigrationAdapterProperties properties;

  /**
   * Built by the registry which registers the workflows, once per application.
   * <p>
   * Both of these may be missing where a test registers workflow services directly. Then
   * nothing is refused, because neither the values which travel nor the declarations can be
   * read.
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
   * Ends the startup where a value travels whose type the BPMS may not give back unchanged,
   * and which nobody declared. It is called once per registered workflow, AFTER the sync
   * model was checked. The annotations decide which values leave at all. Without that set,
   * the types of the wrong values would be judged.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID being registered
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class declaring it
   * @param workflowAggregateClass The workflow aggregate of that workflow
   * @param aggregateIdAttribute The name of the aggregate's ID attribute, or
   *          <code>null</code> where the persistence does not name one
   * @param adapters The adapters which serve this workflow. They are asked what their BPMS
   *          does with a type
   * @throws IllegalStateException Naming every value, its type, its direction, why it does
   *           not travel well, and the two ways forward
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
   * The values of the aggregate on their way to the BPMS. A <code>boolean</code> and a text
   * travel. Everything else has to be declared first.
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
    // FULL is the default of every adapter. So an aggregate which annotates nothing is
    // walked as a whole. The sync model's own check starts from the same point
    for (final var shared : ((AggregateSyncSupport) aggregateSync)
        .valuesSharedWithBpms(workflowAggregateClass, AggregateSyncMode.FULL)) {
      if (shared.path().equals(idAttribute)) {
        // the aggregate's id reaches the BPMS whatever the sync model says. How it travels
        // is the adapter's business (see WorkflowAggregateSync)
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
   * The values which arrive in <code>&#64;TaskParam</code> parameters. Here the
   * serialization decides. So it is enough if an adapter says that its BPMS hands the type
   * back unchanged.
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
   * Whether a value of that type means the same in every expression language which a BPMS
   * may bring. Two types do: the plain <code>boolean</code>, and a text.
   *
   * @param type The declared type
   * @return Whether it travels to the BPMS without being declared
   */
  private static boolean travelsInEveryExpressionLanguage(
      final Class<?> type) {

    return (type == boolean.class) || CharSequence.class.isAssignableFrom(type);

  }

  /**
   * Why that type does not travel to the BPMS on its own. The wrapper {@link Boolean} gets
   * its own sentence. Without it the rule looks like pedantry.
   * <p>
   * Every other type gets the reason, and the fact that a declaration lets it through. The
   * check judges the missing entry, never the type. {@link DeclaredAggregateValues#covers}
   * only compares paths. Without that sentence the reason reads like a verdict on the type.
   * For a collection it would sound as if it could not be declared at all. A collection, a
   * map and an array get one more sentence, because what is inside them is the next
   * question.
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
    final var reason = """
        Only a boolean and a text mean the same in every expression language, and a value of the \
        aggregate is there to carry a decision. So the value has to be declared. Its type is not \
        the problem: a value of any type travels once it is declared.""";
    if (isCollection(type)) {
      return "%s %s"
          .formatted(
              reason,
              """
                  A collection or a map travels as one value, and every expression language reads \
                  what is inside it in its own way. Declared, it travels as it is.""");
    }
    return reason;

  }

  /**
   * Whether a value of that type holds other values. The message about it adds a sentence
   * for that.
   */
  private static boolean isCollection(
      final Class<?> type) {

    return type.isArray() || Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);

  }

  /**
   * What the adapters of the workflow say about that type, added to a message about a value
   * which does not travel. An adapter which says nothing new is left out. So the message has
   * what a reader can act on.
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
   * Says once, as a warning, which adapters could not judge a type. A startup never ends on
   * that answer. But staying silent would leave the developer believing that the value was
   * checked.
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
   * Whether the declaration names no concrete type. Then what arrives is whatever the
   * serialization of the BPMS produced. An {@link Object} is the plain case. A raw collection,
   * a collection with a wildcard, and a {@link Map} are the same thing one level down.
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
   * The task under which the configuration knows that method. It is <code>null</code> where
   * VanillaBP reads no <code>&#64;TaskParam</code> parameters of the method.
   * <p>
   * A <code>&#64;WorkflowStartedByBpms</code> method has no task around it. So its parameters
   * are declared at the workflow. It is named here anyway, because its parameters come from
   * the BPMS exactly like those of a task.
   */
  private static String taskIdOf(
      final Method method) {

    final var task = method.getAnnotation(WorkflowTask.class);
    if (task != null) {
      return WorkflowTaskScanner.taskConfigurationKeyOf(method, task);
    }
    if (method.getAnnotation(WorkflowTasks.class) != null) {
      // several tasks on one method. Whichever of them is configured, the parameter is the
      // same one. So the method's name is the key which the message hands out
      return method.getName();
    }
    return method.getAnnotation(WorkflowStartedByBpms.class) != null
        ? method.getName()
        : null;

  }

  /**
   * How a message writes a type: its canonical name, so that a reader knows which class is
   * meant and can search for it.
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
