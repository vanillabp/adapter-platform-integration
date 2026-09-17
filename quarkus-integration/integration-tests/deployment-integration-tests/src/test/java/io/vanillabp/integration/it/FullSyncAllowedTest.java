package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.it.FullSyncWithoutPermissionTest.FullSyncAggregate;
import io.vanillabp.integration.it.FullSyncWithoutPermissionTest.FullSyncAggregatePersistence;
import io.vanillabp.integration.it.FullSyncWithoutPermissionTest.FullSyncWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The same application as in {@code FullSyncWithoutPermissionTest}, with the permission
 * written at its workflow. It starts, which is what the permission is for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class FullSyncAllowedTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("full-sync/application.yaml", "application.yaml")
          .addClass(FullSyncAggregate.class)
          .addClass(FullSyncAggregatePersistence.class)
          .addClass(FullSyncWorkflowService.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/FullSyncProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  FullSyncWorkflowService workflowService;

  @Test
  @DisplayName("The permission at the workflow starts the application")
  public void theApplicationStartsWithThePermission() {

    assertNotNull(workflowService, "the application booted with the permission at its workflow");

  }

}
