package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Convention over configuration: a workflow module found in the
 * classpath needs NO <code>vanillabp.workflow-modules.&lt;id&gt;</code> section at
 * all - the section is derived and its BPMN is read from the conventional
 * location.
 */
@ExtendWith(SuppressOutputExtension.class)
public class NoWorkflowModuleConfigurationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("no-workflow-module/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                               // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class)                // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))               // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());    // add mocked adapter

  @Inject
  MigrationAdapterProperties properties;

  @Test
  public void testWorkflowModuleNeedsNoConfiguration() {

    Assertions.assertTrue(
        properties.getWorkflowModules().containsKey("test-module"),
        () -> "expected a derived section for the workflow module found in classpath but got: "
            + properties.getWorkflowModules().keySet());
    Assertions.assertEquals(
        java.util.List.of("classpath*:test-module/processes/test", "classpath*:processes/test"),
        properties
            .getAdapterResourcesLocationsFor("test-module", "test")
            .stream()
            .map(
                io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties.ResourcesLocation::location)
            .toList(),
        "the BPMN location follows the convention, the module's own location first");

  }

}
