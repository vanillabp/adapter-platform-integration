package io.vanillabp.integration.test.deployment;

import jakarta.enterprise.context.RequestScoped;

/**
 * A request-scoped bean accessed by a <code>&#64;WorkflowTask</code> handler:
 * handlers are invoked on adapter threads where no CDI request context is active -
 * the platform's transaction runner has to activate it (Panache/Hibernate session
 * access needs it), otherwise accessing this bean throws a
 * <code>ContextNotActiveException</code>.
 */
@RequestScoped
public class RequestScopedProbe {

  public String ping() {

    return "request-scope-active";

  }

}
