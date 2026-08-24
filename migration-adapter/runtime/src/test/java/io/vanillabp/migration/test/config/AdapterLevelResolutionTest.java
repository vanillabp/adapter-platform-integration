package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.TaskAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Tests the generic most-specific-wins resolution of adapter-scoped properties
 * across the four levels (task &gt; workflow &gt; workflow-module &gt; adapter). The
 * levels are exercised via the builder: workflow-level configuration is still
 * rejected at startup and the task level has no consumer yet - the
 * resolver is the structural foundation both stories build on.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AdapterLevelResolutionTest {

  private static final String ADAPTER_ID = "c8-cloud";

  private static AdapterProperties resources(
      final String resourcesLocation) {

    return AdapterProperties
        .builder()
        .resourcesLocation(resourcesLocation)
        .build();

  }

  /**
   * Builds properties with a value at every one of the four levels.
   */
  private static MigrationAdapterProperties allLevels() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER_ID, AdapterConfigProperties
            .builder()
            .type("camunda8")
            .resourcesLocation("adapter-level")
            .build()))
        .workflowModules(Map.of("loan-approval", WorkflowModuleAdapterProperties
            .builder()
            .adapters(Map.of(ADAPTER_ID, resources("module-level")))
            .workflows(Map.of("LoanApproval", WorkflowAdapterProperties
                .builder()
                .adapters(Map.of(ADAPTER_ID, resources("workflow-level")))
                .tasks(Map.of("assessRisk", TaskAdapterProperties
                    .builder()
                    .adapters(Map.of(ADAPTER_ID, resources("task-level")))
                    .build()))
                .build()))
            .build()))
        .build();
    properties.validateAndLink();
    return properties;

  }

  @Test
  @DisplayName("The task level wins over all others")
  public void taskLevelWins() {

    assertEquals(
        "task-level",
        allLevels().resolveForAdapter(
            "loan-approval", "LoanApproval", "assessRisk", ADAPTER_ID, AdapterProperties::getResourcesLocation));

  }

  @Test
  @DisplayName("Without a task match the workflow level wins")
  public void workflowLevelWinsWithoutTask() {

    final var properties = allLevels();

    assertEquals(
        "workflow-level",
        properties.resolveForAdapter(
            "loan-approval", "LoanApproval", null, ADAPTER_ID, AdapterProperties::getResourcesLocation));
    assertEquals(
        "workflow-level",
        properties.resolveForAdapter(
            "loan-approval", "LoanApproval", "unknownTask", ADAPTER_ID, AdapterProperties::getResourcesLocation));

  }

  @Test
  @DisplayName("Without a workflow match the workflow-module level wins")
  public void moduleLevelWinsWithoutWorkflow() {

    final var properties = allLevels();

    assertEquals(
        "module-level",
        properties.resolveForAdapter(
            "loan-approval", null, null, ADAPTER_ID, AdapterProperties::getResourcesLocation));
    assertEquals(
        "module-level",
        properties.resolveForAdapter(
            "loan-approval", "UnknownProcess", "assessRisk", ADAPTER_ID, AdapterProperties::getResourcesLocation));

  }

  @Test
  @DisplayName("Without any scope match the adapter level wins")
  public void adapterLevelIsTheFallback() {

    final var properties = allLevels();

    assertEquals(
        "adapter-level",
        properties.resolveForAdapter(
            null, null, null, ADAPTER_ID, AdapterProperties::getResourcesLocation));
    assertEquals(
        "adapter-level",
        properties.resolveForAdapter(
            "unknown-module", "LoanApproval", "assessRisk", ADAPTER_ID, AdapterProperties::getResourcesLocation));

  }

  @Test
  @DisplayName("A null value at a specific level falls through to the next less specific one")
  public void nullValueFallsThrough() {

    // the task and workflow levels DECLARE the adapter but do not set the value -
    // the resolution has to fall through to the module level
    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER_ID, AdapterConfigProperties
            .builder()
            .type("camunda8")
            .build()))
        .workflowModules(Map.of("loan-approval", WorkflowModuleAdapterProperties
            .builder()
            .adapters(Map.of(ADAPTER_ID, resources("module-level")))
            .workflows(Map.of("LoanApproval", WorkflowAdapterProperties
                .builder()
                .adapters(Map.of(ADAPTER_ID, resources(null)))
                .tasks(Map.of("assessRisk", TaskAdapterProperties
                    .builder()
                    .adapters(Map.of(ADAPTER_ID, resources(null)))
                    .build()))
                .build()))
            .build()))
        .build();
    properties.validateAndLink();

    assertEquals(
        "module-level",
        properties.resolveForAdapter(
            "loan-approval", "LoanApproval", "assessRisk", ADAPTER_ID, AdapterProperties::getResourcesLocation));

  }

  @Test
  @DisplayName("An unconfigured value resolves to null")
  public void unconfiguredValueResolvesToNull() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER_ID, AdapterConfigProperties
            .builder()
            .type("camunda8")
            .build()))
        .build();

    assertNull(properties.resolveForAdapter(
        "loan-approval", "LoanApproval", "assessRisk", ADAPTER_ID, AdapterProperties::getResourcesLocation));
    assertNull(properties.resolveForAdapter(
        null, null, null, "unknown-adapter", AdapterProperties::getResourcesLocation));

  }

}
