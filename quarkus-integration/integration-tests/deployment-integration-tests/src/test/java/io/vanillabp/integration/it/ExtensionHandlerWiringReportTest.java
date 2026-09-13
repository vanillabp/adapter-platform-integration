package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.extension.EveryWorkflowRunsHere;
import io.vanillabp.integration.test.extension.NoteAggregate;
import io.vanillabp.integration.test.extension.NoteAggregatePersistence;
import io.vanillabp.integration.test.extension.NoteTaskWiringSource;
import io.vanillabp.integration.test.extension.NoteWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about the handler methods of an extension: which method serves which
 * key of which BPMN process, and which of them runs where no key is served at all. The
 * same line the Spring Boot integration writes, measured here because a report the core
 * writes says nothing about a platform ever reaching the place it is written at.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionHandlerWiringReportTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("extension/application.yaml", "application.yaml")
          .addClass(NoteAggregate.class)
          .addClass(NoteAggregatePersistence.class)
          .addClass(NoteWorkflowService.class)
          .addClass(EveryWorkflowRunsHere.class)
          .addClass(NoteTaskWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/NoteProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.INFO.intValue())
      .assertLogRecords(records -> {
        final var reports = records
            .stream()
            .map(record -> record.getMessage() == null
                ? ""
                : record.getMessage())
            .filter(message -> message.contains("serves BPMN process"))
            .toList();
        assertEquals(1, reports.size(), "one line per extension, workflow module and BPMN process: "
            + reports);
        final var line = reports.getFirst();
        assertTrue(line.contains("Extension 'sample'"), line);
        assertTrue(line.contains("@SampleNote"), line);
        assertTrue(line.contains("NoteProcess"), line);
        assertTrue(line.contains("test-module"), line);
        assertTrue(line.contains("noteOfTheUserTaskByElementId"), line);
        assertTrue(line.contains("'%s'".formatted(NoteTaskWiringSource.ACTIVITY_ID)), line);
        assertTrue(line.contains("noteOfTheUserTaskByTaskDefinition"), line);
        assertTrue(line.contains("'%s'".formatted(NoteTaskWiringSource.TASK_DEFINITION)), line);
        assertTrue(line.contains("the first one a method serves wins"), line);
      });

  @Test
  @DisplayName("The boot names the method serving each key of an extension")
  public void theWiringOfTheExtensionIsReported() {

    // the assertion is the one above: it reads the log of the boot, which has happened
    // before any test method of this class runs

  }

}
