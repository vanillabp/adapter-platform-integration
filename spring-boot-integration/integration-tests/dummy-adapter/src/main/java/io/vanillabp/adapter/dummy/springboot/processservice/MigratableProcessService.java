package io.vanillabp.adapter.dummy.springboot.processservice;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AggregatePersistenceAware;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Log-only process service of the dummy adapter. The adapter ID is resolved lazily from
 * the configuration (first adapter of type "dummy") since this bean may be created very
 * early during bootstrapping of the Spring context, before configuration properties
 * beans are bound.
 */
@Slf4j
@RequiredArgsConstructor
public class MigratableProcessService<A> implements io.vanillabp.integration.adapter.spi.MigratableProcessService<A> {

  private final ObjectProvider<MigrationAdapterProperties> properties;

  @Override
  public String getAdapterId() {

    return properties
        .getObject()
        .getAdapters()
        .entrySet()
        .stream()
        .filter(adapter -> DummyAdapterConfiguration.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");

  }

  @Override
  public Boolean isTaskActive(
      final String taskId) {

    log.info("Dummy-Adapter: Checking whether task '{}' is active", taskId);

    return null;

  }

  @Override
  public boolean needsTwoPhaseCommitForStartingWorkflows() {

    return false;

  }

  @Override
  public void startWorkflowPhaseOne(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

    log.info("Dummy-Adapter: Starting workflow (phase one)");

  }

  @Override
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    log.info("Dummy-Adapter: Starting workflow (phase two) for aggregate '{}'", workflowAggregateId);

  }

}
