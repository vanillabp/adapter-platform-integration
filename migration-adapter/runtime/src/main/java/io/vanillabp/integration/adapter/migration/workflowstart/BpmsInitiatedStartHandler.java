package io.vanillabp.integration.adapter.migration.workflowstart;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.handler.HandlerContexts;
import io.vanillabp.integration.adapter.migration.workflowtask.ServedVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;

/**
 * One <code>&#64;WorkflowStartedByBpms</code> method of a workflow service class,
 * with its parameter binders and the start event it serves.
 */
// see decision 1 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class BpmsInitiatedStartHandler {

  private final Class<?> workflowServiceClass;

  private final Method method;

  private final Supplier<Object> workflowServiceBean;

  private final List<HandlerValueSource> binders;

  /**
   * The BPMN id of the start event this method serves, or <code>null</code> for
   * every start event of the process.
   */
  private final String startEventId;

  /**
   * The process versions this method serves, which the whole registry shares with the
   * other handler kinds and with the methods of an extension.
   */
  private final ServedVersions versions;

  BpmsInitiatedStartHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<HandlerValueSource> binders,
      final String startEventId,
      final ServedVersions versions) {

    this.workflowServiceClass = workflowServiceClass;
    this.method = method;
    this.workflowServiceBean = workflowServiceBean;
    this.binders = binders;
    this.startEventId = startEventId;
    this.versions = versions;

  }

  /**
   * The start event this method asked for, read by the registry when it looks for a second
   * method wired to the same one. An accessor per field rather than a whole class of them
   * is decision 1 in the repository's DECISIONS.md.
   *
   * @return The BPMN id of the start event, or <code>null</code> where the method serves
   *         every start event of the process
   */
  public String getStartEventId() {

    return startEventId;

  }

  /**
   * How this method is named in a message: the class and the method, both spelled the way
   * they stand in the application's source, so a reader can go to them.
   *
   * @return The method, for guiding messages
   */
  public String describe() {

    return "%s#%s".formatted(workflowServiceClass.getName(), method.getName());

  }

  /**
   * What this method serves, in the words of a message: one named start event, or every
   * start event of the process. Two methods are a defect only where both
   * describe the same thing, which is why the message quotes this rather than the id
   * alone.
   *
   * @return What this handler is wired to, for guiding messages
   */
  public String describeWiring() {

    return startEventId == null
        ? "every start event"
        : "start event '%s'".formatted(startEventId);

  }

  boolean matchesStartEvent(
      final String eventId) {

    return (startEventId == null) || startEventId.equals(eventId);

  }

  /**
   * The version specification(s) this method names, for messages about a method
   * serving no version the BPMS holds.
   *
   * @return The specifications, comma separated
   */
  String describeVersions() {

    return versions.describe();

  }

  /**
   * The version specification(s) plus, where the method named none itself, the
   * declaration they came from. Every message about an ambiguity or a method serving
   * nothing uses this: a complaint about a range the reader cannot see in front of the
   * method reads like a defect of VanillaBP.
   *
   * @return The specifications and their origin
   */
  String describeVersionsWithOrigin() {

    return versions.describeWithOrigin();

  }

  /**
   * @return Whether this handler serves the range of its class rather than one of its
   *         own
   */
  boolean inheritsVersions() {

    return versions.inherited();

  }

  /**
   * Where the range came from, ready to be appended to a description of the method -
   * empty where the method names its range itself.
   *
   * @return The clause to append, or an empty string
   */
  String describeVersionOrigin() {

    return versions.describeOrigin();

  }

  boolean matchesVersion(
      final String processVersion,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions.matches(processVersion, resolver);

  }

  /**
   * Whether this handler and the given one serve at least one common process version -
   * two handlers serving the same start event are ambiguous exactly then.
   *
   * @param other The other handler
   * @param resolver Resolves version tags of the BPMN process both belong to
   * @return Whether both serve a common version
   */
  boolean overlapsVersions(
      final BpmsInitiatedStartHandler other,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions.overlaps(other.versions, resolver);

  }

  /**
   * @return The version tags this handler's version specifications name
   */
  List<String> versionTags() {

    return versions.versionTags();

  }

  /**
   * @return What this handler serves, for a question the version specifications
   *         answer on their own
   */
  io.vanillabp.integration.adapter.migration.workflowtask.ServedVersions servedVersions() {

    return versions;

  }

  /**
   * Invokes the method with bound parameters. Runtime exceptions of the method
   * propagate unchanged - the transaction of the start rolls back, so nothing was
   * written and the BPMS retries.
   *
   * @param context The adapter's notification
   * @return The workflow aggregate the method built
   */
  Object invoke(
      final BpmsInitiatedStartContext context) {

    // no aggregate is passed: this method is the place it comes into existence
    final var handlerContext = HandlerContexts.of(null, context, context.getVariables());
    final var arguments = binders
        .stream()
        .map(binder -> binder.valueFor(handlerContext))
        .toArray();
    try {
      return method.invoke(workflowServiceBean.get(), arguments);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
          "Could not invoke @WorkflowStartedByBpms method '%s'!".formatted(describe()), e);
    } catch (final InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException(
          "The @WorkflowStartedByBpms method '%s' threw a checked exception!".formatted(describe()), e
              .getTargetException());
    }

  }

}
