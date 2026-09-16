package io.vanillabp.integration.adapter.migration.handler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.workflowtask.ServedVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.extension.spi.handler.HandlerContext;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;

/**
 * One method of a <code>&#64;WorkflowService</code> class an extension's contract
 * matched: the bean it belongs to, the keys it serves and the value sources its
 * parameters were bound to at startup.
 */
final class ExtensionHandlerMethod {

  private final HandlerContract contract;

  private final Class<?> workflowServiceClass;

  private final Method method;

  private final Supplier<Object> workflowServiceBean;

  private final List<HandlerValueSource> parameterBinders;

  /**
   * The keys this method serves. Empty means "the method's name", which is resolved
   * while scanning, so the list is never empty here.
   */
  private final List<String> lookupKeys;

  /**
   * The process versions this method serves - the same mechanics VanillaBP's own handler
   * methods are selected by.
   */
  private final ServedVersions versions;

  ExtensionHandlerMethod(
      final HandlerContract contract,
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<HandlerValueSource> parameterBinders,
      final List<String> lookupKeys,
      final ServedVersions versions) {

    this.contract = contract;
    this.workflowServiceClass = workflowServiceClass;
    this.method = method;
    this.workflowServiceBean = workflowServiceBean;
    this.parameterBinders = parameterBinders;
    this.lookupKeys = lookupKeys;
    this.versions = versions;

  }

  /**
   * @return Whether this method serves every key of its BPMN process
   */
  boolean servesEveryKey() {

    return lookupKeys.contains(HandlerContract.EVERY_KEY);

  }

  /**
   * @param lookupKey One key the caller offered
   * @return Whether this method NAMES it - the catch-all does not count here, which is
   *         what lets a method for one element stand next to it
   */
  boolean serves(
      final String lookupKey) {

    return lookupKeys.contains(lookupKey);

  }

  /**
   * @return The keys this method names, in the order the annotations were read - for the
   *         report about what was wired
   */
  List<String> getLookupKeys() {

    return lookupKeys;

  }

  /**
   * Whether two methods are ambiguous. A method serving one element and a catch-all next
   * to it are not: the specific one wins, which is the rule
   * <code>&#64;WorkflowStartedByBpms</code> follows for its start events too. Two
   * catch-alls, on the other hand, are exactly as ambiguous as two methods naming the
   * same element.
   *
   * @param other Another method of the same contract and BPMN process
   * @return Whether both serve a common key
   */
  boolean overlaps(
      final ExtensionHandlerMethod other) {

    if (servesEveryKey() || other.servesEveryKey()) {
      return servesEveryKey() && other.servesEveryKey();
    }
    return lookupKeys
        .stream()
        .anyMatch(other.lookupKeys::contains);

  }

  /**
   * @param processVersion The version the call names
   * @param resolver Resolves version tags of the BPMN process the method belongs to
   * @return Whether this method serves that version
   */
  boolean matchesVersion(
      final String processVersion,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions.matches(processVersion, resolver);

  }

  /**
   * Whether this method and the given one serve at least one common process version -
   * two methods serving the same key are ambiguous exactly then. Disjoint ranges are a
   * legitimate way of serving several generations of a model.
   *
   * @param other Another method of the same contract and BPMN process
   * @param resolver Resolves version tags of the BPMN process both belong to
   * @return Whether both serve a common version
   */
  boolean overlapsVersions(
      final ExtensionHandlerMethod other,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions.overlaps(other.versions, resolver);

  }

  /**
   * @return Whether this method serves every version, which is what a method naming
   *         none does
   */
  boolean servesEveryVersion() {

    return versions.everyVersion();

  }

  /**
   * @return The version tags this method's specifications name
   */
  List<String> versionTags() {

    return versions.versionTags();

  }

  /**
   * @return The version specification(s), for guiding messages
   */
  String describeVersions() {

    return versions.describe();

  }

  /**
   * @return The annotation these methods carry, for guiding messages
   */
  String describeAnnotation() {

    return "@%s".formatted(contract.getAnnotationType().getSimpleName());

  }

  /**
   * @return The id of the extension this method belongs to
   */
  String getExtensionId() {

    return contract.getExtensionId();

  }

  /**
   * @return The method, for guiding messages
   */
  String describe() {

    return "%s#%s".formatted(workflowServiceClass.getName(), method.getName());

  }

  /**
   * @return What this method is wired to, for guiding messages
   */
  String describeWiring() {

    return lookupKeys.contains(HandlerContract.EVERY_KEY)
        ? "every element of the BPMN process"
        : lookupKeys
            .stream()
            .map("'%s'"::formatted)
            .collect(java.util.stream.Collectors.joining(", "));

  }

  /**
   * Binds the parameters and invokes the method. Runtime exceptions of the method
   * propagate unchanged - only the extension knows whether its BPMS repeats the
   * notification behind them.
   *
   * @param context The values of this invocation
   * @return What the method returned, or <code>null</code> for a void one
   */
  Object invoke(
      final HandlerContext context) {

    final var arguments = parameterBinders
        .stream()
        .map(binder -> binder.valueFor(context))
        .toArray();
    try {
      return method.invoke(workflowServiceBean.get(), arguments);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
          "Could not invoke the @%s method '%s' of extension '%s'!"
              .formatted(contract.getAnnotationType().getSimpleName(), describe(), contract.getExtensionId()), e);
    } catch (final InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (e.getTargetException() instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(
          "The @%s method '%s' of extension '%s' threw a checked exception!"
              .formatted(contract.getAnnotationType().getSimpleName(), describe(),
                  contract.getExtensionId()), e.getTargetException());
    }

  }

}
