package io.vanillabp.integration.test.cancelation;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the derived-cancellation tests. It writes down what reached
 * the application and in which order, which is what those tests read instead of a log.
 */
@Getter
@Setter
public class CancelAggregate {

  private String id;

  /**
   * What the application was told about this workflow, in the order it arrived and
   * separated by a comma: one entry per task reported as canceled, and
   * <code>ended</code> where the <code>&#64;WorkflowEnded</code> method ran.
   */
  @NoSyncWithBPMS
  private String whatArrived;

  /**
   * Appends one thing which arrived.
   *
   * @param what What arrived
   */
  public void arrived(
      final String what) {

    whatArrived = whatArrived == null
        ? what
        : whatArrived
            + ","
            + what;

  }

}
