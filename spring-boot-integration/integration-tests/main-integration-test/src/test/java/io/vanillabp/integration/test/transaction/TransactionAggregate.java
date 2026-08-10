package io.vanillabp.integration.test.transaction;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the transaction-contract acceptance test (story 40b). Lives in its
 * own package for the same classpath-scan reason as the other test aggregates of this
 * module: {@code @WorkflowService} classes leak into every test here, so each scenario
 * keeps its own aggregate and its own BPMN process.
 */
@Getter
@Setter
public class TransactionAggregate {

  private String id;

  private String status;

}
