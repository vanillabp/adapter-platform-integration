package io.vanillabp.integration.adapter.migration.processservice;

/**
 * The composite process-definition id of VanillaBP's viewer API.
 * <p>
 * <b>Scheme:</b> <code>&lt;adapter id&gt;#&lt;adapter-native definition
 * id&gt;</code> - the adapter id up to the FIRST <code>#</code>, everything
 * behind it belongs to the adapter (adapter-native ids may contain any
 * character, e.g. Camunda 7's <code>Demo:1:8a9c...</code>; adapter ids are
 * configuration keys and never contain a <code>#</code>).
 * <p>
 * <b>Why:</b> {@code ProcessService#getBpmnXml(String)} addresses a process
 * DEFINITION, not a workflow - there is no aggregate to elect the BPMS by. In a
 * BPMS migration (several adapters serving the same BPMN process) the id alone
 * therefore has to name the adapter which can resolve it. Ids handed to the
 * application are namespaced (also the one inside
 * {@code WorkflowHistory#processDefinitionId()}); the adapters only ever see
 * their native ids.
 * <p>
 * The scheme is a PERSISTED-LIKE CONTRACT in the sense that applications may
 * store definition ids (e.g. in a viewer's URL): keep it stable.
 */
public final class ProcessDefinitionIds {

  /**
   * Separates the adapter id from the adapter-native definition id.
   */
  public static final char SEPARATOR = '#';

  private ProcessDefinitionIds() {
  }

  /**
   * Namespaces an adapter-native definition id with its adapter id.
   *
   * @param adapterId The adapter's id
   * @param nativeProcessDefinitionId The adapter-native definition id
   * @return The composite id handed to the application
   */
  public static String compose(
      final String adapterId,
      final String nativeProcessDefinitionId) {

    if (nativeProcessDefinitionId == null) {
      return null;
    }
    return adapterId + SEPARATOR + nativeProcessDefinitionId;

  }

  /**
   * Splits a composite definition id.
   *
   * @param processDefinitionId The composite id
   * @return The parts or <code>null</code> if the id does not follow the scheme
   */
  public static Parsed parse(
      final String processDefinitionId) {

    if (processDefinitionId == null) {
      return null;
    }
    final var separator = processDefinitionId.indexOf(SEPARATOR);
    if ((separator < 1) || (separator == (processDefinitionId.length() - 1))) {
      return null;
    }
    return new Parsed(
        processDefinitionId.substring(0, separator), processDefinitionId.substring(separator + 1));

  }

  /**
   * @param adapterId The adapter id addressed by the composite id
   * @param nativeProcessDefinitionId The adapter-native definition id
   */
  public record Parsed(
                       String adapterId,
                       String nativeProcessDefinitionId) {
  }

}
