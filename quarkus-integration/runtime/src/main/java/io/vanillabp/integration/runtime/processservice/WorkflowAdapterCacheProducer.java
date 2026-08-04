package io.vanillabp.integration.runtime.processservice;

import io.quarkus.arc.DefaultBean;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the default {@link WorkflowAdapterCache} consulted by the BPMS election
 * for operations on existing workflows: a bounded, expiring in-memory cache.
 * Cluster setups wanting instances to share elections define their own bean
 * implementing {@link WorkflowAdapterCache} backed by their own cache
 * infrastructure - it replaces this default ({@link DefaultBean}; entries are
 * hints: a stale entry costs an extra probe, never correctness).
 */
@ApplicationScoped
public class WorkflowAdapterCacheProducer {

  @Produces
  @Singleton
  @DefaultBean
  public WorkflowAdapterCache workflowAdapterCache() {

    return new InMemoryWorkflowAdapterCache();

  }

}
