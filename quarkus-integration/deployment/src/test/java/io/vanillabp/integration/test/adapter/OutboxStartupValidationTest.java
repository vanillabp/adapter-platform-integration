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
 * Startup validation of the phase-two outbox: everything an application sends to its
 * BPMS is dispatched after the caller's transaction committed, so it needs a store to
 * plan those calls in. This application has none - neither a JDBC datasource nor a
 * MongoDB client is available, and it brings no store of its own - so the BOOT has to
 * fail with a guiding message naming the aggregate and all remedies instead of
 * surfacing the gap at the first workflow start. The green counterpart is every other
 * test of this module: they bring {@link TestPhaseTwoOutbox}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxStartupValidationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(TestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .assertException(exception -> {

        final var stringWriter = new java.io.StringWriter();
        exception.printStackTrace(new java.io.PrintWriter(stringWriter));
        final var failure = stringWriter.toString();

        Assertions.assertTrue(
            failure.contains("is dispatched through a PhaseTwoOutbox"),
            "expected the guiding startup message but got: "
                + failure);
        // the message names the aggregate and ALL remedies
        Assertions.assertTrue(failure.contains("io.vanillabp.integration.test.samples.sample.Aggregate"));
        Assertions.assertTrue(failure.contains("quarkus-agroal"));
        Assertions.assertTrue(failure.contains("quarkus-mongodb-client"));
        Assertions.assertTrue(failure.contains("PhaseTwoOutbox"));
        Assertions.assertTrue(failure.contains("PhaseTwoOutboxAware"));

      });

  /**
   * The startup has to fail with the remedies - the assertion happens on the boot
   * failure above.
   */
  @Test
  public void anApplicationWithoutOutboxFailsAtStartupWithRemedies() {
    // the assertException callback holds the assertions
  }

}
