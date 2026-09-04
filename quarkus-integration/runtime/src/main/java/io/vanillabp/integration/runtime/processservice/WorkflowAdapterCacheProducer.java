package io.vanillabp.integration.runtime.processservice;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.arc.DefaultBean;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the default {@link WorkflowAdapterCache} consulted by the BPMS election
 * for operations on existing workflows: a bounded, expiring in-memory cache sized by
 * <code>vanillabp.workflow-adapter-cache.*</code>. Cluster setups share their
 * elections instead, either with the implementation VanillaBP ships for Hazelcast or
 * with a bean of their own implementing {@link WorkflowAdapterCache} - either
 * replaces this default ({@link DefaultBean}; entries are hints: a stale entry costs
 * an extra probe, never correctness).
 * <p>
 * The statistics produced here belong to the application rather than to a cache: the
 * process services count what the election asked of WHATEVER cache is in use into
 * this one instance (see
 * {@code io.vanillabp.integration.adapter.migration.processservice.InstrumentedWorkflowAdapterCache}).
 * What an implementation knows about itself is its own, and the in-memory cache
 * reports it under a prefix of its own.
 * <p>
 * The configuration is READ, not injected: injecting the config mapping would make
 * it a static-init mapping, and SmallRye would then validate the shared
 * <code>vanillabp.*</code> tree before the adapter extensions registered their
 * run-time overlays - every adapter-specific key would fail the startup as unknown.
 */
@ApplicationScoped
public class WorkflowAdapterCacheProducer {

  @Produces
  @Singleton
  public WorkflowAdapterCacheStatistics workflowAdapterCacheStatistics() {

    return new WorkflowAdapterCacheStatistics();

  }

  @Produces
  @Singleton
  @DefaultBean
  public WorkflowAdapterCache workflowAdapterCache() {

    return new InMemoryWorkflowAdapterCache(cacheProperties());

  }

  private WorkflowAdapterCacheProperties cacheProperties() {

    return QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(
        ConfigProvider
            .getConfig()
            .unwrap(SmallRyeConfig.class)
            .getConfigMapping(QuarkusMigrationAdapterProperties.class)
            .workflowAdapterCache());

  }

}
