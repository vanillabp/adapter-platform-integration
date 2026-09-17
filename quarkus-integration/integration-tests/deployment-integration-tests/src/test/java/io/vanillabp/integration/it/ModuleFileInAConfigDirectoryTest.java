package io.vanillabp.integration.it;

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
 * A workflow module which ships its file as <i>config/test-module.yaml</i>. Spring Boot
 * reads that place and so does this extension, which is the whole point of decision 65 in
 * the repository's DECISIONS.md: the four places are styles, and VanillaBP prescribes
 * none of them. The other three are measured by
 * {@code WorkflowModuleConfigLocationsTest} of the runtime module.
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
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  Config config;

  @Test
  @DisplayName("The settings of a file in 'config/' arrive")
  public void theSettingsOfTheFileAreThere() {

    Assertions.assertEquals(
        "from-the-file",
        config.getValue("test-module.a-setting", String.class));

  }

}
