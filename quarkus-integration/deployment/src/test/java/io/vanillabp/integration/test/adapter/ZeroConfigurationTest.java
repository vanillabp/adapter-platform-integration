package io.vanillabp.integration.test.adapter;

import java.util.List;
import java.util.Map;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The headline on Quarkus: an application with ONE adapter extension and ONE
 * workflow module needs no <code>vanillabp.*</code> property at all - the archive
 * of this test carries no configuration file whatsoever.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ZeroConfigurationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // NO application.yaml: the classpath is the configuration
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)                               // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class)                // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(DerivedAdapterIdProcessService.class))             // process service of the DERIVED adapter id
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());    // add mocked adapter

  @Inject
  MigrationAdapterProperties properties;

  @Test
  @DisplayName("One adapter extension, one workflow module, ZERO properties: everything is derived")
  public void zeroConfigurationIsDerivedFromTheClasspath() {

    Assertions.assertEquals(
        Map.of("dummy", "dummy"),
        properties.adapterTypes(),
        "the single adapter type of the classpath becomes the one configured adapter");
    Assertions.assertEquals(List.of("dummy"), properties.getPrioritizedAdapters());
    Assertions.assertTrue(
        properties.getWorkflowModules().containsKey("test-module"),
        () -> "the workflow module found in classpath needs no section but got: "
            + properties.getWorkflowModules().keySet());
    // the application IS the workflow module here (the test archive is the root
    // application archive), so its BPMN lives below 'processes/<adapter id>' - and
    // below '<module id>/processes/<adapter id>' when the module is being tested
    // inside its own Maven module, which is the main artifact as well
    Assertions.assertEquals(
        List.of("classpath*:test-module/processes/dummy", "classpath*:processes/dummy"),
        properties
            .getAdapterResourcesLocationsFor("test-module", "dummy")
            .stream()
            .map(
                io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties.ResourcesLocation::location)
            .toList());

  }

}
