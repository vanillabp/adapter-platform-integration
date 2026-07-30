package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Round-trip test pinning the GENERATED {@code toCore()} mapping: every property of
 * the Quarkus {@code @ConfigMapping} interface has to arrive in the core model.
 * Adding a property to only one side already fails the BUILD (the mapper is
 * generated with {@code unmappedSourcePolicy}/{@code unmappedTargetPolicy} ERROR);
 * this test additionally pins the VALUE semantics (Optional unwrapping, empty-list
 * defaults, nested maps).
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusMigrationAdapterPropertiesMapperTest {

  private record AdapterConfiguration(
                                      Optional<String> type,
                                      Optional<DeploymentFailurePolicy> deploymentFailure) implements QuarkusMigrationAdapterProperties.AdapterConfiguration {
  }

  private record AdapterProperties(
                                   String resourcesLocation) implements QuarkusMigrationAdapterProperties.AdapterProperties {
  }

  private record WorkflowProperties(
                                    Optional<List<String>> prioritizedAdapters) implements QuarkusMigrationAdapterProperties.WorkflowProperties {
  }

  private record WorkflowModuleProperties(
                                          Optional<List<String>> prioritizedAdapters,
                                          Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters,
                                          Map<String, QuarkusMigrationAdapterProperties.WorkflowProperties> workflows) implements QuarkusMigrationAdapterProperties.WorkflowModuleProperties {
  }

  private record Properties(
                            Optional<List<String>> prioritizedAdapters,
                            Optional<String> resourcesLocation,
                            Map<String, QuarkusMigrationAdapterProperties.AdapterConfiguration> adapters,
                            Map<String, QuarkusMigrationAdapterProperties.WorkflowModuleProperties> workflowModules,
                            QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties outbox) implements QuarkusMigrationAdapterProperties {
  }

  @Test
  @DisplayName("Every interface property arrives in the core model")
  public void fullTreeArrivesInCoreModel() {

    final var properties = new Properties(
        Optional.of(List.of("c8-cloud", "c7")), Optional.of("classpath*:vanillabp-processes"), Map.of(
            "c8-cloud", new AdapterConfiguration(
                Optional.of("camunda8"), Optional.of(DeploymentFailurePolicy.WARN)),
            "c7", new AdapterConfiguration(Optional.empty(), Optional.empty())), Map.of(
                "loan-approval", new WorkflowModuleProperties(
                    Optional.of(List.of("c7")), Map.of("c7", new AdapterProperties("classpath:c7-bpmn")), Map
                        .of("LoanApproval", new WorkflowProperties(Optional.of(List.of("c8-cloud")))))), null);

    final var core = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);

    assertEquals(List.of("c8-cloud", "c7"), core.getPrioritizedAdapters());
    assertEquals("classpath*:vanillabp-processes", core.getResourcesLocation());
    assertEquals(
        Map.of(
            "c8-cloud", "camunda8",
            "c7", "c7"),
        core.adapterTypes());
    assertEquals(DeploymentFailurePolicy.WARN, core.getDeploymentFailureFor("c8-cloud"));
    assertEquals(DeploymentFailurePolicy.FAIL, core.getDeploymentFailureFor("c7"));

    final var module = core.getWorkflowModules().get("loan-approval");
    assertEquals(List.of("c7"), module.getPrioritizedAdapters());
    assertEquals("classpath:c7-bpmn", module.getAdapters().get("c7").getResourcesLocation());
    assertEquals(
        List.of("c8-cloud"),
        module.getWorkflows().get("LoanApproval").getPrioritizedAdapters());

  }

  @Test
  @DisplayName("Empty optionals map to the core model's defaults")
  public void emptyOptionalsMapToDefaults() {

    final var properties = new Properties(
        Optional.empty(), Optional.empty(), Map.of(), Map.of(), null);

    final var core = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);

    assertTrue(core.getPrioritizedAdapters().isEmpty());
    assertNull(core.getResourcesLocation());
    assertTrue(core.getAdapters().isEmpty());
    assertTrue(core.getWorkflowModules().isEmpty());

  }

}
