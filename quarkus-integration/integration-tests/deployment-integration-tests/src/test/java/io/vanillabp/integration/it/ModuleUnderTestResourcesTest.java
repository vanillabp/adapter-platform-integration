package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * A workflow module tested inside its own Maven module IS the root
 * application archive, so the convention used to drop the module ID and look at
 * 'classpath*:processes/&lt;adapter&gt;' - while the files sit where the packaged
 * application needs them, below the module ID. The module found nothing in its own
 * test, and the failure arrived later from the BPMS.
 * <p>
 * Both locations are searched now, the module's own one first. No property is
 * configured here on purpose: this is the convention doing it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ModuleUnderTestResourcesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("module-under-test/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingDeploymentEvents.class)
          // where the PACKAGED application needs them: below the module ID
          .addAsResource("bpmn/first.bpmn", "test-module/processes/demo/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  RecordingDeploymentEvents events;

  @Test
  @DisplayName("The module's own location is searched although the module is the main artifact")
  public void resourcesBelowTheModuleIdAreFound() {

    final var recorded = events.getEvents();

    assertTrue(
        recorded.contains("adapter:demo:readBpmn:test-module:first.bpmn"),
        () -> "the BPMN below the module ID was not found: "
            + recorded);
    assertTrue(
        recorded.contains("adapter:demo:deployResources:test-module"),
        () -> "the module was not deployed: "
            + recorded);

  }

}
