package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.WorkflowTask;

/**
 * One of two classes which split the handlers of one BPMN process. Both of them are workflow
 * services by inheritance.
 */
public class HandlersOfTheFirstHalf extends SharedProcessBase {

  @WorkflowTask
  public void firstHalf(
      final SharedProcessAggregate aggregate) {

    aggregate.setServedBy(aggregate.getServedBy()
        + "+firstHalf");

  }

}
