package io.vanillabp.integration.test.transaction;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the acceptance test for the transaction contract. It lives in its own
 * package, like the other test aggregates of this module, because of the classpath scan.
 * {@code @WorkflowService} classes leak into every test here. So each scenario keeps its own
 * aggregate and its own BPMN process.
 */
@Getter
@Setter
public class TransactionAggregate {

  private String id;

  private String status;

}
