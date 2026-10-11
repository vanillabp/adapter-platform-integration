package io.vanillabp.integration.test.transaction;

import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.service.TaskException;

/**
 * A bean of the application with its own transaction annotation. A
 * <code>&#64;WorkflowTask</code> handler calls it. The startup check cannot see this case,
 * because the annotation sits three calls down and not on the handler. Its interceptor joins
 * VanillaBP's transaction. As soon as an exception passes the interceptor, it marks the
 * transaction rollback-only.
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
