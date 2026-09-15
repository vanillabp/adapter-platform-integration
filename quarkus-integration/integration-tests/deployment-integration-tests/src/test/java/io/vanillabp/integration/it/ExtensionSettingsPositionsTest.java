package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.test.extension.EveryWorkflowRunsHere;
import io.vanillabp.integration.test.extension.NoteAggregate;
import io.vanillabp.integration.test.extension.NoteAggregatePersistence;
import io.vanillabp.integration.test.extension.NoteTaskWiringSource;
import io.vanillabp.integration.test.extension.NoteWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The eight positions an extension setting may be written at, in a booted application
 * which runs TWO adapters of the same BPMS type. Each position is written once, so every
 * assertion says which one won, and the second adapter says that a value of one adapter
 * never reaches the other (decision 53 in the repository's DECISIONS.md).
 * <p>
 * This application starting at all is part of the measurement: SmallRye refuses a key no
 * mapping knows below <code>vanillabp</code>, so a position the resolver offers and the
 * mapping does not declare would not go unread here, it would end the startup. What such
 * a key looks like is {@code UnknownExtensionSettingsKeyTest} in the deployment module.
 * <p>
 * Measured on Quarkus here and on Spring Boot by the test of the same name there: that
 * the core resolves the positions says nothing about a platform binding all of them.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionSettingsPositionsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "NoteProcess";

  private static final String TASK = "theTask";

  private static final String ADAPTER = "saas";

  private static final String OTHER_ADAPTER = "on-premise";

  private static final String EXTENSION = "sample";

  /**
   * The key every one of the eight positions writes, each with the name of its position.
   */
  private static final String POSITION = "position";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("extension-settings/application.yaml", "application.yaml")
          .addClass(NoteAggregate.class)
          .addClass(NoteAggregatePersistence.class)
          .addClass(NoteWorkflowService.class)
          .addClass(EveryWorkflowRunsHere.class)
          .addClass(NoteTaskWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/NoteProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  MigrationAdapterProperties properties;

  private String resolved(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId) {

    return properties
        .resolveForExtension(workflowModuleId, bpmnProcessId, taskId, adapterId, EXTENSION, POSITION);

  }

  @Test
  @DisplayName("A value written only at the application reaches every scope")
  public void theApplicationReachesEveryScope() {

    final var key = "only-at-the-application";
    final var written = "nobody else writes this";

    assertEquals(written, properties.resolveForExtension(null, null, null, null, EXTENSION, key));
    assertEquals(written, properties.resolveForExtension(MODULE, null, null, null, EXTENSION, key));
    assertEquals(written, properties.resolveForExtension(MODULE, PROCESS, null, null, EXTENSION, key));
    assertEquals(written, properties.resolveForExtension(MODULE, PROCESS, TASK, null, EXTENSION, key));
    assertEquals(written, properties.resolveForExtension(MODULE, PROCESS, TASK, ADAPTER, EXTENSION, key));

  }

  @Test
  @DisplayName("The four general positions: the deeper level wins")
  public void theDeeperLevelWins() {

    assertEquals("application", resolved(null, null, null, null));
    assertEquals("workflow-module", resolved(MODULE, null, null, null));
    assertEquals("workflow", resolved(MODULE, PROCESS, null, null));
    assertEquals("task", resolved(MODULE, PROCESS, TASK, null));

  }

  @Test
  @DisplayName("The four adapter positions: what an adapter is told wins over its own level")
  public void theAdapterSectionWinsOverItsLevel() {

    assertEquals("application/saas", resolved(null, null, null, ADAPTER));
    assertEquals("workflow-module/saas", resolved(MODULE, null, null, ADAPTER));
    assertEquals("workflow/saas", resolved(MODULE, PROCESS, null, ADAPTER));
    assertEquals("task/saas", resolved(MODULE, PROCESS, TASK, ADAPTER));

  }

  @Test
  @DisplayName("The second adapter of the same type is told its own values")
  public void theSecondAdapterReadsItsOwnValues() {

    assertEquals("application/on-premise", resolved(null, null, null, OTHER_ADAPTER));
    assertEquals("workflow-module/on-premise", resolved(MODULE, null, null, OTHER_ADAPTER));
    assertEquals("workflow/on-premise", resolved(MODULE, PROCESS, null, OTHER_ADAPTER));
    assertEquals("task/on-premise", resolved(MODULE, PROCESS, TASK, OTHER_ADAPTER));

  }

  @Test
  @DisplayName("An adapter nobody configured reads the general positions")
  public void anUnconfiguredAdapterReadsTheGeneralPositions() {

    assertEquals("task", resolved(MODULE, PROCESS, TASK, "no-such-adapter"));

  }

  @Test
  @DisplayName("A key nobody writes is null rather than the value of a neighbour")
  public void whatNobodyWritesIsNull() {

    assertNull(
        properties.resolveForExtension(MODULE, PROCESS, TASK, ADAPTER, EXTENSION, "nobody-writes-this"));
    assertNull(
        properties.resolveForExtension(MODULE, PROCESS, TASK, ADAPTER, "no-such-extension", POSITION));

  }

}
