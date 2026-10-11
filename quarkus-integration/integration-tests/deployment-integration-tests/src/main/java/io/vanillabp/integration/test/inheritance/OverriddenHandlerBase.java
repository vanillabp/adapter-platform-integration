package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.WorkflowTask;

/**
 * Declares a handler which the subclass overrides. Java never inherits the annotation of
 * a method. So the override, which does not repeat it, makes the handler disappear.
 */
public abstract class OverriddenHandlerBase {

  @WorkflowTask
  public void overriddenTask(
      final InvisibleHandlersAggregate aggregate) {

    aggregate.setServedBy("the base");

  }

}
