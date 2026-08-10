package io.vanillabp.integration.test.transaction;

import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.service.TaskException;

/**
 * A bean of the application carrying its own transaction annotation, called by a
 * <code>&#64;WorkflowTask</code> handler. This is the shape the startup check cannot
 * see: the annotation sits three calls down, not on the handler. Its interceptor joins
 * VanillaBP's transaction and marks it rollback-only as soon as an exception passes
 * it.
 */
@Transactional
public class NestedTransactionalBean {

  public void raiseTaskException() {

    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  public void fail() {

    throw new IllegalStateException("the nested call failed");

  }

}
