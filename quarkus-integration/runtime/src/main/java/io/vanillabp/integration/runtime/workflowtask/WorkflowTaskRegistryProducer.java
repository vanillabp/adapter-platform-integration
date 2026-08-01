package io.vanillabp.integration.runtime.workflowtask;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the core-owned {@link WorkflowTaskRegistry} (the adapter-facing
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker}):
 * the generated process-service beans register every workflow service class under
 * all BPMN process IDs it declares; adapters validate the wiring during
 * <code>wireBpmn</code> and dispatch task invocations at runtime. Registered by the
 * platform's build step independently of any adapter.
 */
@ApplicationScoped
public class WorkflowTaskRegistryProducer {

  @Produces
  @Singleton
  @Unremovable
  public WorkflowTaskRegistry workflowTaskRegistry() {

    return new WorkflowTaskRegistry(new QuarkusTransactionRunner());

  }

}
