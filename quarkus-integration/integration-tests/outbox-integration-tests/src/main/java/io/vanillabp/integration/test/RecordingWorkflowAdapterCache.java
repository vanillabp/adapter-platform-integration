package io.vanillabp.integration.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.integration.spi.WorkflowAdapterCache;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * An election cache which the application provides (the SPI for a cache shared in a
 * cluster). It replaces VanillaBP's {@code @DefaultBean} in memory. It records every
 * access, so tests can check that it is the one which is asked.
 */
@ApplicationScoped
public class RecordingWorkflowAdapterCache implements WorkflowAdapterCache {

  private final Map<String, String> entries = new ConcurrentHashMap<>();

  private final List<String> puts = new CopyOnWriteArrayList<>();

  private final List<String> gets = new CopyOnWriteArrayList<>();

  private String key(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, workflowAggregateId);

  }

  public List<String> getPuts() {

    return List.copyOf(puts);

  }

  public List<String> getGets() {

    return List.copyOf(gets);

  }

  @Override
  public Optional<String> get(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var key = key(workflowModuleId, bpmnProcessId, workflowAggregateId);
    gets.add(key);
    return Optional.ofNullable(entries.get(key));

  }

  @Override
  public void put(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId) {

    final var key = key(workflowModuleId, bpmnProcessId, workflowAggregateId);
    puts.add(key
        + "->"
        + adapterId);
    entries.put(key, adapterId);

  }

  @Override
  public void invalidate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    entries.remove(key(workflowModuleId, bpmnProcessId, workflowAggregateId));

  }

}
