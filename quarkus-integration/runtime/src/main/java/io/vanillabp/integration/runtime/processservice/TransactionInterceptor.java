package io.vanillabp.integration.runtime.processservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Interceptor applied to all workflow task methods (methods annotated by
 * @{@link io.vanillabp.spi.service.WorkflowTask}). The interceptor binding
 * @{@link VanillaBpTaskInterception} is added to those methods at build time by an
 * annotation transformer of the deployment module, since <code>&#64;WorkflowTask</code>
 * itself is not an interceptor binding.
 * <p>
 * <strong>Note:</strong> This interceptor is currently a placeholder for transaction
 * handling to be implemented later. For now it only logs the invocation of workflow
 * task methods.
 */
@VanillaBpTaskInterception
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_AFTER)
public class TransactionInterceptor {

  private static final Logger log = LoggerFactory.getLogger(TransactionInterceptor.class);

  @AroundInvoke
  @SuppressWarnings("unused")
  public Object aroundInvokeCheckForTransaction(
      final InvocationContext invocationContext) throws Exception {

    log.info("Before {}", invocationContext.getMethod().getName());
    final var result = invocationContext.proceed();
    log.info("After {}", invocationContext.getMethod().getName());
    return result;

  }

}
