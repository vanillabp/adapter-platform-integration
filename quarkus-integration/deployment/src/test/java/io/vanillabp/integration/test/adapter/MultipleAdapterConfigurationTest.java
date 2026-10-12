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
 * A prioritized adapter id which nobody serves ends the boot. Two ids of ONE type are
 * configured here. The mocked adapter only provides a process service for 'test', but
 * 'test2' is prioritized first.
 * <p>
 * The check runs AT STARTUP. There an observer of the startup event validates the process
 * services. Its message names the id which nobody serves. A workflow must never silently
 * start in some other BPMS which happened to answer. An adapter which serves both ids boots
 * green. {@link Test2ListProcessServiceProducer} is the other half of this.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MultipleAdapterConfigurationTest {

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
   * The startup has to fail fast, because the prioritized adapter id 'test2' has no
   * matching {@code MigratableProcessService}. The assertions check the boot failure
   * above.
   */
  @Test
  public void testFailFastOnUnservedPrioritizedAdapterId() {
    // the assertException callback holds the assertions
  }

}
