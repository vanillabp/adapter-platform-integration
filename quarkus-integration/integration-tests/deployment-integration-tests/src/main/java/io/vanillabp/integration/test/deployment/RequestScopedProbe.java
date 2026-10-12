package io.vanillabp.integration.test.deployment;

import jakarta.enterprise.context.RequestScoped;

/**
 * A request-scoped bean which a <code>&#64;WorkflowTask</code> handler uses. Handlers run
 * on adapter threads, and there no CDI request context is active. So the platform's
 * transaction runner has to activate it. Access to a Panache or Hibernate session needs
 * it. Otherwise, using this bean throws a <code>ContextNotActiveException</code>.
 */
@RequestScoped
public class RequestScopedProbe {

  public String ping() {

    return "request-scope-active";

  }

}
