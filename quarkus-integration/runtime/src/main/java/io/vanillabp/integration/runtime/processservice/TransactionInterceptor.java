package io.vanillabp.integration.runtime.processservice;

import org.slf4j.LoggerFactory;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Typically an Interceptor needs an Annotation for interceptor binding. Since the
 * annotation @WorkflowTask it is used for binding which is not an interceptor binding
 * annotation, the missing annotation will be added during deployment by
 * VanillaBpIntegrationProcessor. Therefore, &quot;CdiInterceptorInspection&quot;
 * warnings have to be suppressed.
 */
@SuppressWarnings("CdiInterceptorInspection")
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_AFTER)
public class TransactionInterceptor {

  @AroundInvoke
  @SuppressWarnings("unused")
  public Object aroundInvokeCheckForTransaction(
      final InvocationContext invocationContext) throws Exception {

    LoggerFactory.getLogger(this.getClass()).info("Before {}", invocationContext.getMethod().getName());
    final var result = invocationContext.proceed();
    LoggerFactory.getLogger(this.getClass()).info("After {}", invocationContext.getMethod().getName());
    return result;

  }

}
