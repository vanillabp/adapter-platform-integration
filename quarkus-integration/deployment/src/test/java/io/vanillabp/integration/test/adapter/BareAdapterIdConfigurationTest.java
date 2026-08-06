package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * An adapter section consisting of nothing but the adapter id relies on the
 * convention "the adapter id IS the adapter type". It configures the adapter on
 * Spring Boot, but NOT on Quarkus: the configuration binding derives the keys of a
 * properties map from the property NAMES below its prefix, and a section without
 * any property contributes no name - the id never reaches the runtime and the
 * situation cannot be detected.
 * <p>
 * Since story 34 that limitation no longer hurts the documented setups: with ONE
 * adapter type in the classpath the adapter is derived from the classpath anyway,
 * so the application boots exactly as the developer intended. (A CUSTOM bare id
 * still cannot be derived - see {@link BareCustomAdapterIdConfigurationTest}.)
 */
@ExtendWith(SuppressOutputExtension.class)
public class BareAdapterIdConfigurationTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("bare-adapter-id/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                               // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class)                // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(DerivedAdapterIdProcessService.class))             // process service of the DERIVED adapter id
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());    // add mocked adapter

  @Inject
  MigrationAdapterProperties properties;

  @Test
  public void testBareAdapterIdBootsViaTheConvention() {

    Assertions.assertEquals(
        java.util.Map.of("dummy", "dummy"),
        properties.adapterTypes(),
        "the single adapter type of the classpath is derived");
    Assertions.assertEquals(java.util.List.of("dummy"), properties.getPrioritizedAdapters());

  }

}
