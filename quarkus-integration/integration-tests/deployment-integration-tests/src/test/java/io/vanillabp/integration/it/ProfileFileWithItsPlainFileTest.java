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
 * The same workflow module as in {@code ProfileFileWithoutItsPlainFileTest}, with
 * <i>test-module.yaml</i> added: the settings of <i>test-module-test.yaml</i> are there,
 * and nothing is reported. It is the other half of the measurement, and it is what keeps
 * the report from being written for a module which is fine.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ProfileFileWithItsPlainFileTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("profile-file/application.yaml", "application.yaml")
          .addAsResource("profile-file/test-module.yaml", "test-module.yaml")
          .addAsResource("profile-file/test-module-test.yaml", "test-module-test.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
      .assertLogRecords(records -> Assertions.assertTrue(
          records
              .stream()
              .map(record -> record.getMessage() == null
                  ? ""
                  : record.getMessage())
              .noneMatch(message -> message.contains("test-module-test.yaml")),
          "a module whose files Quarkus reads is reported all the same"));

  @Inject
  Config config;

  @Test
  @DisplayName("The settings of the profile file and of the plain file are both there")
  public void bothFilesAreRead() {

    Assertions.assertEquals(
        "from-the-modules-profile-file",
        config.getValue("test-module.profile-only", String.class));
    Assertions.assertEquals(
        "from-the-modules-plain-file",
        config.getValue("test-module.plain-only", String.class));

  }

}
