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
 * Produces the default {@link WorkflowAdapterCache}. The BPMS election asks it for
 * operations on existing workflows. It is a cache in memory with a size limit and with
 * entries which expire. <code>vanillabp.workflow-adapter-cache.*</code> sets its size. A
 * cluster shares its elections instead. It uses the implementation which VanillaBP ships
 * for Hazelcast, or a bean of its own which implements {@link WorkflowAdapterCache}. Either
 * one replaces this default ({@link DefaultBean}). Entries are hints: an outdated entry
 * costs an extra probe, but it never makes a result wrong.
 * <p>
 * The statistics produced here belong to the application, not to a cache. The process
 * services count what the election asked of WHATEVER cache is in use into this one instance
 * (see
 * {@code io.vanillabp.integration.adapter.migration.processservice.InstrumentedWorkflowAdapterCache}).
 * What an implementation knows about itself is its own business. The cache in memory reports
 * it under a prefix of its own.
 * <p>
 * The configuration is READ, not injected. Injecting the config mapping would make it a
 * static-init mapping. Then SmallRye would check the shared <code>vanillabp.*</code> tree
 * before the adapter extensions registered their run-time overlays. Every adapter-specific
 * key would then fail the startup as unknown.
 */
@ApplicationScoped
public class WorkflowAdapterCacheProducer {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   * The producer reads its configuration itself, so nothing is injected into it.
   */
  public WorkflowAdapterCacheProducer() {
  }

  /**
   * The one place where these numbers are created. They count what the election asked of
   * whatever cache is in use. So an application which replaces the cache keeps the same
   * numbers.
   *
   * @return The counters of the application, empty at this point
   */
  @Produces
  @Singleton
  public WorkflowAdapterCacheStatistics workflowAdapterCacheStatistics() {

    return new WorkflowAdapterCacheStatistics();

  }

  /**
   * The cache which an application gets when it says nothing. A bean of its own replaces this
   * one ({@link DefaultBean}). This is how a cluster shares its elections. Such a cache holds
   * hints. So replacing it costs probes, but it never makes a result wrong (see decision 5 in
   * the repository's DECISIONS.md).
   *
   * @return The in-memory cache, sized by <code>vanillabp.workflow-adapter-cache.*</code>
   */
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
