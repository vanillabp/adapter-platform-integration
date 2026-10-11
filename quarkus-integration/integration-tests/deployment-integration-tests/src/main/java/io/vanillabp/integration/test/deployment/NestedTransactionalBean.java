package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.TaskException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * A bean of the application with its own transaction annotation. A
 * <code>&#64;WorkflowTask</code> handler calls it. The startup check cannot see this case,
 * because the annotation sits further down the call chain, not on the handler. Its
 * interceptor joins VanillaBP's transaction. It marks the transaction rollback-only as
 * soon as an exception passes it.
 */
@ApplicationScoped
@Transactional
public class NestedTransactionalBean {

  public void raiseTaskException() {

    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  public void fail() {

    throw new IllegalStateException("the nested call failed");

  }

}
