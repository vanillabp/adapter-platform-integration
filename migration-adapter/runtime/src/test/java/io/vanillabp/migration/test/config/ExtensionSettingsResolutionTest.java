package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.TaskAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an extension is told, and where it may be told it: the four levels of
 * {@code vanillabp.extensions.<extension>.*}, resolved by the same rule an adapter
 * setting follows (decision 7 in the repository's DECISIONS.md).
 * <p>
 * The platform-neutral half of the story. That both platforms bind the four levels is
 * held by {@code VanillaBpConfigurationBindingTest} on Spring Boot and by
 * {@code ExtensionEnablementTest} on Quarkus.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionSettingsResolutionTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String TASK = "awaitSignature";

  private static final String EXTENSION = "cockpit";

  /**
   * One value set at every level, and one value set only at the root - so every
   * assertion says both which level won and that the rest of the section survived.
   */
  private static MigrationAdapterProperties properties() {

    final var task = TaskAdapterProperties
        .builder()
        .extensions(Map.of(EXTENSION, Map.of("title", "of the task")))
        .build();
    final var workflow = WorkflowAdapterProperties
        .builder()
        .tasks(Map.of(TASK, task))
        .extensions(Map.of(EXTENSION, Map.of("title", "of the workflow")))
        .build();
    final var module = WorkflowModuleAdapterProperties
        .builder()
        .workflows(Map.of(PROCESS, workflow))
        .extensions(Map.of(EXTENSION, Map.of("title", "of the module")))
        .build();
    return MigrationAdapterProperties
        .builder()
        .workflowModules(Map.of(MODULE, module))
        .extensions(Map.of(EXTENSION, Map.of("title", "of the application", "base-url", "http://cockpit")))
        .build();

  }

  @Test
  @DisplayName("A setting written only at the root reaches every level")
  public void theRootReachesEveryLevel() {

    final var properties = properties();

    assertEquals("http://cockpit", properties.resolveForExtension(null, null, null, EXTENSION, "base-url"));
    assertEquals("http://cockpit", properties.resolveForExtension(MODULE, null, null, EXTENSION, "base-url"));
    assertEquals("http://cockpit", properties.resolveForExtension(MODULE, PROCESS, null, EXTENSION, "base-url"));
    assertEquals("http://cockpit", properties.resolveForExtension(MODULE, PROCESS, TASK, EXTENSION, "base-url"));

  }

  @Test
  @DisplayName("The most specific level which says something wins")
  public void theMostSpecificLevelWins() {

    final var properties = properties();

    assertEquals("of the application", properties.resolveForExtension(null, null, null, EXTENSION, "title"));
    assertEquals("of the module", properties.resolveForExtension(MODULE, null, null, EXTENSION, "title"));
    assertEquals("of the workflow", properties.resolveForExtension(MODULE, PROCESS, null, EXTENSION, "title"));
    assertEquals("of the task", properties.resolveForExtension(MODULE, PROCESS, TASK, EXTENSION, "title"));

  }

  @Test
  @DisplayName("A level which says nothing about a key keeps what a less specific one says")
  public void theLevelsAreMergedKeyByKey() {

    final var settings = properties().extensionProperties(MODULE, PROCESS, TASK, EXTENSION);

    assertEquals(
        Map.of("title", "of the task", "base-url", "http://cockpit"),
        settings);

  }

  @Test
  @DisplayName("An id nobody configured is skipped, not an error")
  public void unknownIdsFallBackToTheLessSpecificLevels() {

    final var properties = properties();

    assertEquals("of the application", properties.resolveForExtension("other-module", null, null, EXTENSION, "title"));
    assertEquals("of the module", properties.resolveForExtension(MODULE, "OtherProcess", null, EXTENSION, "title"));
    assertEquals("of the workflow", properties.resolveForExtension(MODULE, PROCESS, "otherTask", EXTENSION, "title"));

  }

  @Test
  @DisplayName("A key nobody wrote is null, and an extension nobody configured has no settings")
  public void whatNobodyConfiguredIsNull() {

    final var properties = properties();

    assertNull(properties.resolveForExtension(MODULE, PROCESS, TASK, EXTENSION, "no-such-key"));
    assertNull(properties.resolveForExtension(MODULE, PROCESS, TASK, "no-such-extension", "title"));
    assertTrue(properties.extensionProperties(MODULE, PROCESS, TASK, "no-such-extension").isEmpty());

  }

  @Test
  @DisplayName("The two-argument form is the four-level one asked about the module alone")
  public void theShortFormAsksTheSameResolution() {

    final var properties = properties();

    assertEquals(
        properties.extensionProperties(MODULE, null, null, EXTENSION),
        properties.extensionProperties(MODULE, EXTENSION));
    assertEquals("of the module", properties.extensionProperty(MODULE, EXTENSION, "title"));

  }

  @Test
  @DisplayName("The answer is read-only, so a caller cannot change what the next one reads")
  public void theAnswerIsReadOnly() {

    final var settings = properties().extensionProperties(MODULE, PROCESS, TASK, EXTENSION);

    assertThrows(UnsupportedOperationException.class, () -> settings.put("title", "mine"));

  }

}
