package io.vanillabp.integration.spi;

/**
 * What a row of the {@link TaskDeliveryLog} is about. It is the KIND of the row and not the
 * result of anything: {@link TaskDelivery#outcome()} keeps saying what a delivery reported,
 * and a row which reports nothing says so here.
 * <p>
 * Two kinds today. A task delivery is what the log was built for, and a workflow start is the
 * row written when a workflow of an aggregate begins, so the BPMS' own id of that workflow can
 * be read back without asking a BPMS. The two are told apart by this field rather than by a
 * value borrowed from the outcome, because every question about open work filters on it and a
 * borrowed value would make a start look like a delivery to each of them.
 * <p>
 * Why the row says its kind in a field of its own is decision 108 in the repository's
 * DECISIONS.md.
 */
public enum DeliveryRecordKind {

  /**
   * A task the application was handed and what its handler reported. This is what a row
   * written before the kind existed is, so a store reading no kind reads this one.
   */
  TASK_DELIVERY,

  /**
   * The start of a workflow: it carries the workflow aggregate and the BPMS' own id of the
   * workflow, and nothing about a task. No question about open work counts it, and it
   * outlives the workflow it names - see
   * {@link TaskDeliveryLog#workflowStartOf(String, String, String)}.
   */
  WORKFLOW_START;

  /**
   * The kind a row names, read back from a store.
   *
   * @param recordedKind The text the row holds, <code>null</code> in a row written before the
   *          field existed
   * @return The kind, {@link #TASK_DELIVERY} where the row names none, and <code>null</code>
   *         where it names something this version does not know (a row written by a newer
   *         VanillaBP)
   */
  public static DeliveryRecordKind of(
      final String recordedKind) {

    if (recordedKind == null) {
      return TASK_DELIVERY;
    }
    for (final var kind : values()) {
      if (kind.name().equals(recordedKind)) {
        return kind;
      }
    }
    return null;

  }

}
