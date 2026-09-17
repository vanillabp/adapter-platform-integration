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
 * A workflow module which ships <i>test-module-test.yaml</i> and no
 * <i>test-module.yaml</i>, with the profile <i>test</i> active: the settings of that file
 * are nowhere, and the application says so at startup naming the file it misses.
 * {@code ProfileFileWithItsPlainFileTest} is the same application with the plain file
 * added, where the same settings do arrive.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ProfileFileWithoutItsPlainFileTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("profile-file/application.yaml", "application.yaml")
          .addAsResource("profile-file/test-module-test.yaml", "test-module-test.yaml")
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
                .anyMatch(message -> message.contains("test-module-test.yaml") && message
                    .contains("'test-module.yaml'") && message.contains("An empty file is enough")),
            "expected the WARN naming the unread file and the file it needs but got: "
                + messages);
      });

  @Inject
  Config config;

  @Test
  @DisplayName("The application boots and none of the settings of the profile file are there")
  public void theSettingsOfTheProfileFileAreNotThere() {

    Assertions.assertTrue(
        config.getOptionalValue("test-module.profile-only", String.class).isEmpty(),
        "the file was read after all, so the warning about it would be wrong");

  }

}
