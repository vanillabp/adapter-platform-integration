package io.vanillabp.integration.it;

import java.util.logging.Level;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The measurement behind the report: a workflow module which ships its file as
 * <i>config/test-module.yaml</i>. Spring Boot reads that place, Quarkus does not.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ModuleFileInAConfigDirectoryTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("config-directory/application.yaml", "application.yaml")
          .addAsResource("config-directory/test-module.yaml", "config/test-module.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
      .assertLogRecords(records -> {
        final var messages = records
            .stream()
            .map(record -> record.getMessage() == null
                ? ""
                : record.getMessage())
            .toList();
        Assertions.assertTrue(
            messages
                .stream()
                .anyMatch(message -> message.contains("config/test-module.yaml") && message
                    .contains("which belongs at 'test-module.yaml'")),
            "expected the WARN naming the unread file and the place it belongs at but got: "
                + messages);
      });

  @Inject
  Config config;

  @Test
  @DisplayName("None of the settings of a file in 'config/' are there")
  public void theSettingsOfTheFileAreNotThere() {

    Assertions.assertTrue(
        config.getOptionalValue("test-module.from-a-config-directory", String.class).isEmpty(),
        "Quarkus read the file after all");

  }

}
