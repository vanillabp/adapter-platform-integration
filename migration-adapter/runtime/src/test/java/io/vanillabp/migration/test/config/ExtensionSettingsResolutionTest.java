package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * What an extension is told, and where it may be told it: the four levels of
 * {@code vanillabp.extensions.<extension>.*} and the adapter section of each of them,
 * resolved by the same rule an adapter setting follows (decision 7 in the repository's
 * DECISIONS.md) and by the walk every plug-in uses (decision 53).
 * <p>
 * The platform-neutral half of the story. That both platforms bind all eight positions is
 * held by {@code ExtensionSettingsPositionsTest} on Spring Boot and by
 * {@code ExtensionSettingsPositionsTest} on Quarkus.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionSettingsResolutionTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String TASK = "awaitSignature";

  private static final String EXTENSION = "cockpit";

  private static final String ADAPTER = "saas";

  private static final String OTHER_ADAPTER = "on-premise";

  /**
   * One value set at every level, one value set at the adapter section of every level,
   * and one value set only at the root - so every assertion says both which position won
   * and that the rest of the section survived.
   */
  private static MigrationAdapterProperties properties() {

    final var task = TaskAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, adapterSaying("of the task, on this adapter")))
        .extensions(Map.of(EXTENSION, Map.of("title", "of the task")))
        .build();
    final var workflow = WorkflowAdapterProperties
        .builder()
        .tasks(Map.of(TASK, task))
        .adapters(Map.of(ADAPTER, adapterSaying("of the workflow, on this adapter")))
        .extensions(Map.of(EXTENSION, Map.of("title", "of the workflow")))
        .build();
    final var module = WorkflowModuleAdapterProperties
        .builder()
        .workflows(Map.of(PROCESS, workflow))
        .adapters(Map.of(ADAPTER, adapterSaying("of the module, on this adapter")))
        .extensions(Map.of(EXTENSION, Map.of("title", "of the module")))
        .build();
    return MigrationAdapterProperties
        .builder()
        .workflowModules(Map.of(MODULE, module))
        .adapters(
            Map
                .of(
                    ADAPTER,
                    AdapterConfigProperties
                        .builder()
                        .type("dummy")
                        .extensions(Map.of(EXTENSION, Map.of("title", "of the application, on this adapter")))
                        .build(),
                    OTHER_ADAPTER,
                    AdapterConfigProperties.ofType("dummy")))
        .extensions(Map.of(EXTENSION, Map.of("title", "of the application", "base-url", "http://cockpit")))
        .build();

  }

  private static AdapterProperties adapterSaying(
      final String title) {

    return AdapterProperties
        .builder()
        .extensions(Map.of(EXTENSION, Map.of("title", title)))
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


  @Test
  @DisplayName("What one adapter is told beats what the same level says in general")
  public void theAdapterSectionBeatsTheGeneralSection() {

    final var properties = properties();

    assertEquals(
        "of the application, on this adapter",
        properties.resolveForExtension(null, null, null, ADAPTER, EXTENSION, "title"));
    assertEquals(
        "of the module, on this adapter",
        properties.resolveForExtension(MODULE, null, null, ADAPTER, EXTENSION, "title"));
    assertEquals(
        "of the workflow, on this adapter",
        properties.resolveForExtension(MODULE, PROCESS, null, ADAPTER, EXTENSION, "title"));
    assertEquals(
        "of the task, on this adapter",
        properties.resolveForExtension(MODULE, PROCESS, TASK, ADAPTER, EXTENSION, "title"));

  }

  @Test
  @DisplayName("What one adapter is told never reaches another one")
  public void theOtherAdapterReadsTheGeneralSections() {

    final var properties = properties();

    assertEquals(
        "of the application",
        properties.resolveForExtension(null, null, null, OTHER_ADAPTER, EXTENSION, "title"));
    assertEquals(
        "of the task",
        properties.resolveForExtension(MODULE, PROCESS, TASK, OTHER_ADAPTER, EXTENSION, "title"));

  }

  @Test
  @DisplayName("A more specific level beats the adapter section of a less specific one")
  public void theLevelOutranksTheAdapterSectionAboveIt() {

    final var properties = properties();

    // the workflow says nothing about this adapter, so the workflow's general section
    // wins over what the module told the adapter
    assertEquals(
        "of the workflow",
        properties.resolveForExtension(MODULE, PROCESS, null, OTHER_ADAPTER, EXTENSION, "title"));

  }

  @Test
  @DisplayName("The adapter positions are merged key by key like every other one")
  public void theAdapterPositionsMergeKeyByKey() {

    final var settings = properties().extensionProperties(MODULE, PROCESS, TASK, ADAPTER, EXTENSION);

    assertEquals(
        Map.of("title", "of the task, on this adapter", "base-url", "http://cockpit"),
        settings);

  }

}
