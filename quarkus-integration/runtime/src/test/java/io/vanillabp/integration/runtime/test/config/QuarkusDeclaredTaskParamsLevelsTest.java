package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where a QUARKUS application may write <code>declared-task-params</code>, and which of
 * the four places wins. The rule is the core's, and a booted Spring Boot application
 * holds it end to end ({@code PortableValuesTest} of the main integration test). What
 * this asks is whether the answer survives the way to it: the config mapping and the
 * generated mapper onto the core model. A level which binds but never arrives looks
 * exactly like a level which works.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusDeclaredTaskParamsLevelsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String TASK = "assessRisk";

  private static final String WORKFLOW_KEY = "vanillabp.workflow-modules."
      + MODULE
      + ".workflows."
      + PROCESS;

  private static MigrationAdapterProperties configured(
      final Map<String, String> properties) {

    final var config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .withSources(new PropertiesConfigSource(properties, "test", 500))
        .build();
    return QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(config.getConfigMapping(QuarkusMigrationAdapterProperties.class));

  }

  private static List<String> resolved(
      final Map<String, String> properties) {

    return configured(properties).declaredTaskParams(MODULE, PROCESS, TASK);

  }

  @Test
  @DisplayName("What the application declares covers a task which declares nothing")
  public void theApplicationLevelArrives() {

    assertEquals(
        List.of("riskReport"),
        resolved(Map.of("vanillabp.declared-task-params", "riskReport")));

  }

  @Test
  @DisplayName("A workflow module which declares nothing does not hide what the application declares")
  public void aSilentWorkflowModuleDoesNotHideTheApplication() {

    assertEquals(
        List.of("riskReport"),
        resolved(
            Map
                .of(
                    "vanillabp.declared-task-params", "riskReport",
                    "vanillabp.workflow-modules."
                        + MODULE
                        + ".adapters.demo1.resources-location",
                    "classpath:processes/dummy")));

  }

  @Test
  @DisplayName("What the workflow module declares beats what the application declares")
  public void theWorkflowModuleLevelWinsOverTheApplication() {

    assertEquals(
        List.of("ofTheModule"),
        resolved(
            Map
                .of(
                    "vanillabp.declared-task-params", "ofTheApplication",
                    "vanillabp.workflow-modules."
                        + MODULE
                        + ".declared-task-params",
                    "ofTheModule")));

  }

  @Test
  @DisplayName("What the workflow declares beats what the workflow module declares")
  public void theWorkflowLevelWinsOverTheWorkflowModule() {

    assertEquals(
        List.of("ofTheWorkflow"),
        resolved(
            Map
                .of(
                    "vanillabp.workflow-modules."
                        + MODULE
                        + ".declared-task-params",
                    "ofTheModule",
                    WORKFLOW_KEY
                        + ".declared-task-params",
                    "ofTheWorkflow")));

  }

  @Test
  @DisplayName("What the task declares beats every level above it")
  public void theTaskLevelWinsOverEverythingAbove() {

    assertEquals(
        List.of("ofTheTask"),
        resolved(
            Map
                .of(
                    "vanillabp.declared-task-params", "ofTheApplication",
                    "vanillabp.workflow-modules."
                        + MODULE
                        + ".declared-task-params",
                    "ofTheModule",
                    WORKFLOW_KEY
                        + ".declared-task-params",
                    "ofTheWorkflow",
                    WORKFLOW_KEY
                        + ".tasks."
                        + TASK
                        + ".declared-task-params",
                    "ofTheTask")));

  }

  @Test
  @DisplayName("A task nobody declared anything for reads an empty list")
  public void nothingDeclaredAnywhereIsAnEmptyList() {

    assertEquals(List.of(), resolved(Map.of("vanillabp.adapters.demo1.type", "dummy")));

  }

}
