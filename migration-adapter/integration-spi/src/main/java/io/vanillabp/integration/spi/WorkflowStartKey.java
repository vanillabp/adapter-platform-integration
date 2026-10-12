package io.vanillabp.integration.spi;

/**
 * The key under which the row about the start of a workflow is stored (see
 * {@link DeliveryRecordKind#WORKFLOW_START}). It is built from the workflow module, the BPMN
 * process and the workflow aggregate. That is everything the reader of that row knows. So the
 * row is found by its key, and no query is needed:
 *
 * <pre>
 * &lt;workflowModuleId&gt;|&lt;bpmnProcessId&gt;|(workflow-start)|&lt;workflowAggregateId&gt;
 * </pre>
 *
 * <strong>No delivery can ever get it.</strong> The key of a task delivery has five parts:
 * the delivering adapter first, and the delivery the BPMS reported last. Its THIRD part is the
 * BPMN process id, and that is where {@value #MARKER} stands here. No BPMN process has that
 * name, because brackets are not allowed in an XML id. A row of a delivery nobody deduplicates
 * says the same thing in the same place, with a word of its own. Both words are there, so a
 * reader of the store sees from the key what the row is.
 * <p>
 * <strong>Why it is derived and not random.</strong> A delivery nobody deduplicates adds a
 * random value, because two deliveries of one task must not share a row. A start is the
 * opposite. Deriving the key makes the read a lookup by primary key, and not a query over a
 * column which is too wide to index. And a start dispatched twice writes nothing, instead of
 * a second row.
 * <p>
 * The key does NOT carry the workflow. So there is one row per aggregate and BPMN process.
 * An aggregate which carries a SECOND workflow once the first one ended reaches the row of that
 * first one. The row is then overwritten and not refused. That is what
 * {@link TaskDeliveryLog#recordWorkflowStart} is for.
 * <p>
 * A key longer than {@value #MAX_LENGTH} characters is replaced by a hash of itself, through
 * {@link StoredKey}. The reason is the same as for the key of a task delivery: a store indexes
 * the key, and a unique index has a maximum key length. The two limits are the same number
 * on purpose, and a test checks them against each other.
 */
public final class WorkflowStartKey {

  /**
   * What stands in the key where the key of a task delivery carries the BPMN process id. It
   * is not a valid BPMN process id or adapter id of any BPMS, so it can never collide with
   * one.
   */
  public static final String MARKER = "(workflow-start)";

  /**
   * Up to this length the key is stored as it reads. A longer one is hashed. It is the same
   * number which limits the key of a task delivery, because both live in the same column.
   */
  public static final int MAX_LENGTH = 512;

  private WorkflowStartKey() {

  }

  /**
   * The key of the start row of one workflow.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @return The key, never <code>null</code>
   */
  public static String of(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return StoredKey
        .of(
            "%s|%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, MARKER, workflowAggregateId),
            MAX_LENGTH);

  }

}
