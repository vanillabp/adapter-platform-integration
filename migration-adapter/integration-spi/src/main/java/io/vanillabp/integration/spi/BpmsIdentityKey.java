package io.vanillabp.integration.spi;

/**
 * The key under which the identity of a BPMS is stored (see
 * {@link DeliveryRecordKind#BPMS_IDENTITY}). There is one such row per adapter, workflow module
 * and BPMN process, because the startup checks of VanillaBP run per BPMN process:
 *
 * <pre>
 * &lt;adapterId&gt;|&lt;workflowModuleId&gt;|(bpms-identity)|&lt;bpmnProcessId&gt;
 * </pre>
 *
 * <strong>No delivery can ever get it.</strong> The THIRD part of the key of a task delivery is
 * the BPMN process id, and that is where {@value #MARKER} stands here. No BPMN process has that
 * name, because brackets are not allowed in an XML id. The same trick keeps the key of a
 * workflow start apart from every delivery.
 * <p>
 * A key longer than {@value WorkflowStartKey#MAX_LENGTH} bytes of UTF-8 is replaced by a hash of
 * itself, through {@link StoredKey}, for the same reason as the key of a workflow start: both
 * live in the same indexed column.
 */
public final class BpmsIdentityKey {

  /**
   * What stands in the key where the key of a task delivery carries the BPMN process id. It
   * is not a valid BPMN process id, so it can never collide with one.
   */
  public static final String MARKER = "(bpms-identity)";

  private BpmsIdentityKey() {

  }

  /**
   * The key of the identity row of one adapter, as the startup check of one BPMN process
   * writes and reads it.
   *
   * @param adapterId The ID of the adapter whose BPMS the identity is of
   * @param workflowModuleId The ID of the workflow module of the check
   * @param bpmnProcessId The BPMN process ID of the check
   * @return The key, never <code>null</code>
   */
  public static String of(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return StoredKey
        .of(
            "%s|%s|%s|%s".formatted(adapterId, workflowModuleId, MARKER, bpmnProcessId),
            WorkflowStartKey.MAX_LENGTH);

  }

}
