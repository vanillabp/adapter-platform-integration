package io.vanillabp.integration.spi;

/**
 * Turns the identifiers the application writes into the identifiers a BPMS knows, and back.
 * The workflow module decides how, with its name-clash-avoidance mode
 * (<code>vanillabp.workflow-modules.&lt;module&gt;.name-clash-avoidance</code>). The core
 * implements it ONCE, so every adapter and every extension scopes the same way.
 * <p>
 * In the modes <code>none</code> and <code>by-adapter</code>, every <code>scoped*</code>
 * method returns its input unchanged. So it is always safe to call them.
 *
 * <h2>Who asks</h2>
 *
 * An extension which talks to a BPMS itself asks it, and it asks only this interface. A
 * query to a BPMS has to spell an identifier the way the BPMS stores it. A BPMS adapter
 * gets the same methods through <code>NameClashAvoidanceSupport</code> of the adapter SPI,
 * which extends this interface and adds what only an adapter needs while it deploys. An
 * extension never takes <code>NameClashAvoidanceSupport</code>.
 *
 * <h2>How to get it</h2>
 *
 * Both platform integrations publish the core's object under this type as well. So ask for
 * a bean of this type like for any other bean: a constructor parameter on Spring Boot, an
 * injection point or a producer parameter on Quarkus.
 * <p>
 * <b>Never build the strings yourself.</b> The core owns the separator and how the parts
 * are put together, and the startup check for colliding identifiers relies on it. The
 * object may be missing, in a unit test or in a component built without a platform around
 * it. For that case, the STATIC methods of the same names take it as their first argument.
 * For a <code>null</code> one they answer the plain identifier. So nobody has to write that
 * rule again.
 */
public interface IdentifierScoping {

  /**
   * The separator between a prefix and the identifier it scopes. Two underscores are
   * allowed in an XML {@code NCName}, so BPMN element ids may carry them (unlike e.g.
   * <code>#</code>). And they are unlikely to appear in an identifier by accident.
   */
  String SEPARATOR = "__";

  /**
   * Whether the BPMS keeps the given workflow module apart by its own means, for example
   * by a tenant. That is the mode <code>by-adapter</code>. Which means the BPMS uses is the
   * adapter's knowledge. The core answers only whether this mode applies. The most specific
   * configured value wins (workflow &gt; workflow module &gt; adapter). Without any
   * configuration, the adapter's own default applies.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID or <code>null</code> to ask for the whole
   *          module
   * @param adapterId The adapter ID
   * @return Whether the mode is <code>by-adapter</code>
   */
  boolean isolatesByAdapter(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId);

  /**
   * The BPMN process ID as the BPMS knows it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @return The scoped ID, or the plain one if the mode is not <code>use-prefix</code>
   */
  String scopedProcessId(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId);

  /**
   * An identifier scoped by the workflow module alone - message names, signal
   * names, error codes and escalation codes.
   *
   * @param workflowModuleId The workflow module ID
   * @param identifier The plain identifier (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped identifier, or the plain one if the mode is not
   *         <code>use-prefix</code>
   */
  String scopedIdentifier(
      String workflowModuleId,
      String identifier,
      String adapterId);

  /**
   * A task definition, scoped by the workflow module AND by the BPMN process ID. The
   * application can switch off the second part
   * (<code>prefix-task-definitions-per-process: false</code>). Using one task
   * implementation for several processes is an anti-pattern, so scoping per process is
   * the default.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param taskDefinition The plain task definition (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped task definition, or the plain one if the mode is not
   *         <code>use-prefix</code>
   */
  String scopedTaskDefinition(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinition,
      String adapterId);

  /**
   * The inverse of {@link #scopedProcessId(String, String, String)}: the plain BPMN process
   * ID of what the BPMS reported. It removes a KNOWN prefix (never "everything up to the
   * first separator"). So an identifier without the expected prefix is returned unchanged.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The ID as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain BPMN process ID
   */
  String plainProcessId(
      String workflowModuleId,
      String scopedBpmnProcessId,
      String adapterId);

  /**
   * The inverse of {@link #scopedIdentifier(String, String, String)}.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedIdentifier The identifier as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain identifier
   */
  String plainIdentifier(
      String workflowModuleId,
      String scopedIdentifier,
      String adapterId);

  /**
   * The inverse of {@link #scopedTaskDefinition(String, String, String, String)}. The BPMN
   * process ID is needed, because task definitions are scoped per process by default. Pass
   * the process the task belongs to.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedTaskDefinition The task definition as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain task definition
   */
  String plainTaskDefinition(
      String workflowModuleId,
      String bpmnProcessId,
      String scopedTaskDefinition,
      String adapterId);

  /**
   * {@link #scopedProcessId(String, String, String)} for the case where the object may be
   * missing. A caller which holds it as a field needs this form. It is also the only place
   * where the rule "no scoping, plain identifier" is written down.
   * <p>
   * An adapter or an extension has the object wherever a platform runs it. So a unit test
   * hands in <code>null</code>, and so does a component built without a platform around it.
   * The plain identifier is not a fallback, it is the correct answer. Without a mode there
   * is nothing to prefix by, and <code>none</code> and <code>by-adapter</code> answer the
   * same. If every caller wrote its own check, two of them would end up disagreeing, and a
   * disagreement here is a query which finds nothing.
   *
   * @param scoping The core's identifier scoping, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @return The scoped ID, or the plain one
   */
  static String scopedProcessId(
      final IdentifierScoping scoping,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    return scoping == null
        ? bpmnProcessId
        : scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * {@link #scopedIdentifier(String, String, String)} for the case where the object may be
   * missing. See {@link #scopedProcessId(IdentifierScoping, String, String, String)}.
   *
   * @param scoping The core's identifier scoping, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param identifier The plain identifier (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped identifier, or the plain one
   */
  static String scopedIdentifier(
      final IdentifierScoping scoping,
      final String workflowModuleId,
      final String identifier,
      final String adapterId) {

    return scoping == null
        ? identifier
        : scoping.scopedIdentifier(workflowModuleId, identifier, adapterId);

  }

  /**
   * {@link #scopedTaskDefinition(String, String, String, String)} for the case where the
   * object may be missing. See
   * {@link #scopedProcessId(IdentifierScoping, String, String, String)}.
   *
   * @param scoping The core's identifier scoping, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param taskDefinition The plain task definition (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped task definition, or the plain one
   */
  static String scopedTaskDefinition(
      final IdentifierScoping scoping,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    return scoping == null
        ? taskDefinition
        : scoping.scopedTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);

  }

  /**
   * {@link #plainProcessId(String, String, String)} for the case where the object may be
   * missing. See {@link #scopedProcessId(IdentifierScoping, String, String, String)}.
   *
   * @param scoping The core's identifier scoping, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The ID as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain BPMN process ID
   */
  static String plainProcessId(
      final IdentifierScoping scoping,
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String adapterId) {

    return scoping == null
        ? scopedBpmnProcessId
        : scoping.plainProcessId(workflowModuleId, scopedBpmnProcessId, adapterId);

  }

  /**
   * {@link #plainIdentifier(String, String, String)} for the case where the object may be
   * missing. See {@link #scopedProcessId(IdentifierScoping, String, String, String)}.
   *
   * @param scoping The core's identifier scoping, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param scopedIdentifier The identifier as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain identifier
   */
  static String plainIdentifier(
      final IdentifierScoping scoping,
      final String workflowModuleId,
      final String scopedIdentifier,
      final String adapterId) {

    return scoping == null
        ? scopedIdentifier
        : scoping.plainIdentifier(workflowModuleId, scopedIdentifier, adapterId);

  }

  /**
   * {@link #plainTaskDefinition(String, String, String, String)} for the case where the
   * object may be missing. See
   * {@link #scopedProcessId(IdentifierScoping, String, String, String)}.
   *
   * @param scoping The core's identifier scoping, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedTaskDefinition The task definition as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain task definition
   */
  static String plainTaskDefinition(
      final IdentifierScoping scoping,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedTaskDefinition,
      final String adapterId) {

    return scoping == null
        ? scopedTaskDefinition
        : scoping.plainTaskDefinition(workflowModuleId, bpmnProcessId, scopedTaskDefinition, adapterId);

  }

}
