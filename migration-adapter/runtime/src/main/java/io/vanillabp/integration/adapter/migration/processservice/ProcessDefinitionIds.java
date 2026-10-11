package io.vanillabp.integration.adapter.migration.processservice;

/**
 * The composite process-definition id of VanillaBP's viewer API.
 * <p>
 * <b>Scheme:</b> <code>&lt;adapter id&gt;#&lt;adapter-native definition id&gt;</code>. The
 * adapter id goes up to the FIRST <code>#</code>, and everything behind it belongs to the
 * adapter. The adapter's own ids may contain any character, for example Camunda 7's
 * <code>Demo:1:8a9c...</code>. Adapter ids are configuration keys and never contain a
 * <code>#</code>.
 * <p>
 * <b>Why:</b> {@code ProcessService#getBpmnXml(String)} addresses a process DEFINITION, not a
 * workflow. So there is no aggregate by which to elect the BPMS. In a BPMS migration several
 * adapters serve the same BPMN process. So the id alone has to name the adapter which can
 * resolve it. Ids handed to the application get the adapter id in front (also the one
 * inside {@code WorkflowHistory#processDefinitionId()}). The adapters only ever see their own
 * ids.
 * <p>
 * Applications may store definition ids, for example in the URL of a viewer. So treat the
 * scheme like a persisted contract and keep it stable.
 */
public final class ProcessDefinitionIds {

  /**
   * Separates the adapter id from the adapter-native definition id.
   */
  public static final char SEPARATOR = '#';

  private ProcessDefinitionIds() {
  }

  /**
   * Puts the adapter id in front of a definition id of that adapter.
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
   * The two halves of a composite id, as {@link #parse(String)} read them. Both are always
   * there, because an id whose halves would be empty is not parsed at all.
   *
   * @param adapterId The adapter id addressed by the composite id
   * @param nativeProcessDefinitionId The adapter-native definition id
   */
  public record Parsed(
                       String adapterId,
                       String nativeProcessDefinitionId) {
  }

}
