package io.vanillabp.integration.test.workflowstart;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the acceptance test of workflows the BPMS starts on its own. Its
 * ID is a String, so a timer's trigger time can be its identity.
 */
@Getter
@Setter
public class WorkflowStartAggregate {

  private String id;

  private String region;

  private int amount;

  /**
   * Set by the <code>&#64;WorkflowStartedByBpms</code> method, so a test can tell
   * whether the application had its say or VanillaBP built the aggregate alone.
   */
  private String startedBy;

}
