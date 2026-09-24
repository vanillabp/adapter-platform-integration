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
 * probe is <code>deduplicate-deliveries</code>, the key which is really read at all
 * four of them, and neighbouring levels say the opposite so that every answer names
 * the level it came from.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AdapterLevelResolutionTest {

  private static final String ADAPTER_ID = "c8-cloud";

  private static AdapterProperties deduplicating(
      final Boolean deduplicateDeliveries) {

    return AdapterProperties
        .builder()
        .deduplicateDeliveries(deduplicateDeliveries)
        .build();

  }

  /**
   * Builds properties with a value at every one of the four levels: the task says
   * false, the workflow true, the workflow module false and the adapter true. Each
   * level therefore contradicts the next less specific one, so an answer can only
   * have come from the level the test expects.
   */
  private static MigrationAdapterProperties allLevels() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER_ID, AdapterConfigProperties
            .builder()
            .type("camunda8")
            .deduplicateDeliveries(Boolean.TRUE)
            .build()))
        .workflowModules(Map.of("loan-approval", WorkflowModuleAdapterProperties
            .builder()
            .adapters(Map.of(ADAPTER_ID, deduplicating(Boolean.FALSE)))
            .workflows(Map.of("LoanApproval", WorkflowAdapterProperties
                .builder()
                .adapters(Map.of(ADAPTER_ID, deduplicating(Boolean.TRUE)))
                .tasks(Map.of("assessRisk", TaskAdapterProperties
                    .builder()
                    .adapters(Map.of(ADAPTER_ID, deduplicating(Boolean.FALSE)))
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
        Boolean.FALSE,
        allLevels().resolveForAdapter(
            "loan-approval", "LoanApproval", "assessRisk", ADAPTER_ID, AdapterProperties::getDeduplicateDeliveries));

  }

  @Test
  @DisplayName("Without a task match the workflow level wins")
  public void workflowLevelWinsWithoutTask() {

    final var properties = allLevels();

    assertEquals(
        Boolean.TRUE,
        properties.resolveForAdapter(
            "loan-approval", "LoanApproval", null, ADAPTER_ID, AdapterProperties::getDeduplicateDeliveries));
    assertEquals(
        Boolean.TRUE,
        properties.resolveForAdapter(
            "loan-approval", "LoanApproval", "unknownTask", ADAPTER_ID, AdapterProperties::getDeduplicateDeliveries));

  }

  @Test
  @DisplayName("Without a workflow match the workflow-module level wins")
  public void moduleLevelWinsWithoutWorkflow() {

    final var properties = allLevels();

    assertEquals(
        Boolean.FALSE,
        properties.resolveForAdapter(
            "loan-approval", null, null, ADAPTER_ID, AdapterProperties::getDeduplicateDeliveries));
    assertEquals(
        Boolean.FALSE,
        properties.resolveForAdapter(
            "loan-approval", "UnknownProcess", "assessRisk", ADAPTER_ID, AdapterProperties::getDeduplicateDeliveries));

  }

  @Test
  @DisplayName("Without any scope match the adapter level wins")
  public void adapterLevelIsTheFallback() {

    final var properties = allLevels();

    assertEquals(
        Boolean.TRUE,
        properties.resolveForAdapter(
            null, null, null, ADAPTER_ID, AdapterProperties::getDeduplicateDeliveries));
    assertEquals(
        Boolean.TRUE,
        properties.resolveForAdapter(
            "unknown-module", "LoanApproval", "assessRisk", ADAPTER_ID, AdapterProperties::getDeduplicateDeliveries));

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
            .adapters(Map.of(ADAPTER_ID, deduplicating(Boolean.FALSE)))
            .workflows(Map.of("LoanApproval", WorkflowAdapterProperties
                .builder()
                .adapters(Map.of(ADAPTER_ID, deduplicating(null)))
                .tasks(Map.of("assessRisk", TaskAdapterProperties
                    .builder()
                    .adapters(Map.of(ADAPTER_ID, deduplicating(null)))
                    .build()))
                .build()))
            .build()))
        .build();
    properties.validateAndLink();

    assertEquals(
        Boolean.FALSE,
        properties.resolveForAdapter(
            "loan-approval", "LoanApproval", "assessRisk", ADAPTER_ID, AdapterProperties::getDeduplicateDeliveries));

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
        "loan-approval", "LoanApproval", "assessRisk", ADAPTER_ID, AdapterProperties::getDeduplicateDeliveries));
    assertNull(properties.resolveForAdapter(
        null, null, null, "unknown-adapter", AdapterProperties::getDeduplicateDeliveries));

  }

}
