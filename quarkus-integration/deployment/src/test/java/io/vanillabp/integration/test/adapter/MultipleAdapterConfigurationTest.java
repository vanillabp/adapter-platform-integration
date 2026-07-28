package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.samples.sample.Aggregate;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Documents the current, honest behavior for TWO adapter ids of ONE type (B2
 * regression test at the platform level): the mocked adapter provides a single
 * process service serving adapter id 'test' only, while 'test2' (same type) is
 * prioritized first. The election's fail-fast fires on first use of the process
 * service with a guiding message naming the unserved adapter id - workflows must
 * never silently start in the wrong BPMS. Full per-adapter-id multiplicity is
 * introduced by the adapter-config-model story (26d), which will turn this failure
 * into a green boot.
 */
public class MultipleAdapterConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("multiple-adapters/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                           // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class))            // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter()); // add mocked adapter

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> sampleProcessService;

  /**
   * Creating the process service must fail fast since the prioritized adapter id
   * 'test2' has no matching {@code MigratableProcessService}.
   */
  @Test
  public void testFailFastOnUnservedPrioritizedAdapterId() {

    // the bean is @ApplicationScoped: the client proxy triggers creation on first use
    final var exception = Assertions.assertThrows(
        RuntimeException.class,
        () -> sampleProcessService.getWorkflowModuleId());

    final var stringWriter = new java.io.StringWriter();
    exception.printStackTrace(new java.io.PrintWriter(stringWriter));
    final var failure = stringWriter.toString();

    Assertions.assertTrue(
        failure.contains("No VanillaBP adapter serves the prioritized adapter id 'test2'"),
        "expected the unserved adapter id in the guiding message but got: "
            + failure);
    Assertions.assertTrue(failure.contains("test-module"));
    Assertions.assertTrue(failure.contains("classpath"));
    Assertions.assertTrue(failure.contains("vanillabp.prioritized-adapters"));

  }

}
