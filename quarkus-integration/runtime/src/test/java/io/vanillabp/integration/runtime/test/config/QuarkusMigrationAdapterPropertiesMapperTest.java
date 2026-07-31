package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
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
                                      Optional<DeploymentFailurePolicy> deploymentFailure,
                                      Optional<String> resourcesLocation) implements QuarkusMigrationAdapterProperties.AdapterConfiguration {
  }

  private record AdapterProperties(
                                   Optional<String> resourcesLocation) implements QuarkusMigrationAdapterProperties.AdapterProperties {
  }

  private record TaskProperties(
                                Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters) implements QuarkusMigrationAdapterProperties.TaskProperties {
  }

  private record WorkflowProperties(
                                    Optional<List<String>> prioritizedAdapters,
                                    Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters,
                                    Map<String, QuarkusMigrationAdapterProperties.TaskProperties> tasks) implements QuarkusMigrationAdapterProperties.WorkflowProperties {
  }

  private record WorkflowModuleProperties(
                                          Optional<List<String>> prioritizedAdapters,
                                          Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters,
                                          Map<String, QuarkusMigrationAdapterProperties.WorkflowProperties> workflows) implements QuarkusMigrationAdapterProperties.WorkflowModuleProperties {
  }

  private record JdbcOutboxProperties(
                                      boolean enabled,
                                      Optional<String> table) implements QuarkusMigrationAdapterProperties.JdbcOutboxProperties {
  }

  private record MongoOutboxProperties(
                                       boolean enabled,
                                       String collection) implements QuarkusMigrationAdapterProperties.MongoOutboxProperties {
  }

  private record OutboxProperties(
                                  Duration pollInterval,
                                  Duration attemptFrequency,
                                  int blockAfterAttempts,
                                  boolean createSchema,
                                  Duration retention,
                                  QuarkusMigrationAdapterProperties.JdbcOutboxProperties jdbc,
                                  QuarkusMigrationAdapterProperties.MongoOutboxProperties mongo) implements QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties {
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
                Optional.of("camunda8"), Optional.of(DeploymentFailurePolicy.WARN), Optional.of("adapter-level")),
            "c7", new AdapterConfiguration(Optional.empty(), Optional.empty(), Optional.empty())), Map.of(
                "loan-approval", new WorkflowModuleProperties(
                    Optional.of(List.of("c7")), Map.of("c7",
                        new AdapterProperties(Optional.of("classpath:c7-bpmn"))), Map
                            .of("LoanApproval",
                                new WorkflowProperties(
                                    Optional.of(List.of("c8-cloud")), Map
                                        .of("c8-cloud", new AdapterProperties(Optional.of("workflow-level"))), Map
                                            .of("assessRisk", new TaskProperties(Map
                                                .of("c8-cloud", new AdapterProperties(Optional
                                                    .of("task-level"))))))))), new OutboxProperties(
                                                        Duration.ofSeconds(1), Duration.ofSeconds(2), 3, false, Duration
                                                            .ofDays(1), new JdbcOutboxProperties(false, Optional
                                                                .of("HOT_OUTBOX")), new MongoOutboxProperties(
                                                                    false, "hot-outbox")));

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
    final var workflow = module.getWorkflows().get("LoanApproval");
    assertEquals(List.of("c8-cloud"), workflow.getPrioritizedAdapters());
    assertEquals("workflow-level", workflow.getAdapters().get("c8-cloud").getResourcesLocation());
    assertEquals(
        "task-level",
        workflow.getTasks().get("assessRisk").getAdapters().get("c8-cloud").getResourcesLocation());
    assertEquals("adapter-level", core.getAdapters().get("c8-cloud").getResourcesLocation());

    assertEquals(Duration.ofSeconds(1), core.getOutbox().getPollInterval());
    assertEquals(Duration.ofSeconds(2), core.getOutbox().getAttemptFrequency());
    assertEquals(3, core.getOutbox().getBlockAfterAttempts());
    assertFalse(core.getOutbox().isCreateSchema());
    assertEquals(Duration.ofDays(1), core.getOutbox().getRetention());
    assertFalse(core.getOutbox().getJdbc().isEnabled());
    assertEquals("HOT_OUTBOX", core.getOutbox().getJdbc().getTable());
    assertFalse(core.getOutbox().getMongo().isEnabled());
    assertEquals("hot-outbox", core.getOutbox().getMongo().getCollection());

  }

  @Test
  @DisplayName("The interface's @WithDefault outbox values equal the core defaults")
  public void outboxDefaultsMatchCoreDefaults() {

    // instantiate the REAL mapping with an empty configuration so SmallRye fills
    // the @WithDefault values, map onto the core and compare against the core's
    // field initializers - pins the necessarily duplicated defaults against drift
    final var config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .build();
    final var mappedDefaults = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(
        config
            .getConfigMapping(QuarkusMigrationAdapterProperties.class)
            .outbox());

    final var coreDefaults = new PhaseTwoOutboxProperties();
    assertEquals(coreDefaults.getPollInterval(), mappedDefaults.getPollInterval());
    assertEquals(coreDefaults.getAttemptFrequency(), mappedDefaults.getAttemptFrequency());
    assertEquals(coreDefaults.getBlockAfterAttempts(), mappedDefaults.getBlockAfterAttempts());
    assertEquals(coreDefaults.isCreateSchema(), mappedDefaults.isCreateSchema());
    assertEquals(coreDefaults.getRetention(), mappedDefaults.getRetention());
    assertEquals(coreDefaults.getJdbc().isEnabled(), mappedDefaults.getJdbc().isEnabled());
    assertEquals(coreDefaults.getJdbc().getTable(), mappedDefaults.getJdbc().getTable());
    assertEquals(coreDefaults.getMongo().isEnabled(), mappedDefaults.getMongo().isEnabled());
    assertEquals(coreDefaults.getMongo().getCollection(), mappedDefaults.getMongo().getCollection());

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
