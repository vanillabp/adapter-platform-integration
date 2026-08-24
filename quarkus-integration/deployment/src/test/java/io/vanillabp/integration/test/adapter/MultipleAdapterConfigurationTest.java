package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Documents the current, honest behavior for TWO adapter ids of ONE type (B2
 * regression test at the platform level): the mocked adapter provides a single
 * process service serving adapter id 'test' only, while 'test2' (same type) is
 * prioritized first. The election's fail-fast fires AT STARTUP (the
 * process services are validated by a StartupEvent observer) with a guiding message
 * naming the unserved adapter id - workflows must never silently start in the wrong
 * BPMS. Full per-adapter-id multiplicity is introduced by the adapter-config-model
 * story (26d), which turns this failure into a green boot.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MultipleAdapterConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("multiple-adapters/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                           // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class))            // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter()) // add mocked adapter
      .assertException(exception -> {

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

      });

  /**
   * The startup has to fail fast since the prioritized adapter id 'test2' has no
   * matching {@code MigratableProcessService} - the assertion happens on the boot
   * failure above.
   */
  @Test
  public void testFailFastOnUnservedPrioritizedAdapterId() {
    // the assertException callback holds the assertions
  }

}
