package io.vanillabp.integration.spi;

/**
 * The key the row about the start of a workflow is stored under (see
 * {@link DeliveryRecordKind#WORKFLOW_START}). It is built from the workflow module, the BPMN
 * process and the workflow aggregate, which is everything the reader of that row knows, so the
 * row is found by its key instead of by a query:
 *
 * <pre>
 * &lt;workflowModuleId&gt;|&lt;bpmnProcessId&gt;|(workflow-start)|&lt;workflowAggregateId&gt;
 * </pre>
 *
 * <strong>No delivery can ever fall on it.</strong> The key of a task delivery carries the
 * delivering adapter first and the delivery the BPMS reported last, five parts in all, and no
 * adapter is called {@value #MARKER}. A row of a delivery nobody deduplicates says the same
 * thing in the same place with a word of its own, and both words are there so a reader of the
 * store sees at the key what the row is.
 * <p>
 * <strong>Why it is derived and not random.</strong> A delivery nobody deduplicates adds a
 * random value, because two deliveries of one task must not share a row. A start is the
 * opposite: there is one start per workflow of one aggregate, and deriving the key makes the
 * read a lookup by primary key rather than a query over a column too wide to index. It also
 * makes a repeated start write nothing instead of a second row, which is what an outbox entry
 * dispatched twice would otherwise leave behind.
 * <p>
 * A key longer than {@value #MAX_LENGTH} characters is replaced by a hash of itself, through
 * {@link StoredKey} and for the reason the key of a task delivery is bounded as well: a store
 * indexes the key and a unique index has a maximum key length. The two limits are the same
 * number on purpose, and a test holds them against each other.
 */
public final class WorkflowStartKey {

  /**
   * What stands in the key where a task delivery carries the lifecycle event. It is not a
   * valid BPMN process id or adapter id of any BPMS, so it can never collide with one.
   */
  public static final String MARKER = "(workflow-start)";

  /**
   * Up to this length the key is stored as it reads; a longer one is hashed. The same number
   * the key of a task delivery is bounded by, because both live in the same column.
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
