package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Startup validation of the phase-two outbox (story 26i): the mocked adapter
 * requires a two-phase commit for starting workflows
 * ({@link TwoPhaseTestMigratableProcessService}) but neither a JDBC datasource nor a
 * MongoDB client is available - the BOOT has to fail with a guiding message naming
 * the aggregate and all remedies instead of surfacing the gap at the first workflow
 * start. (The green counterpart - an adapter NOT requiring a two-phase commit boots
 * without any outbox - is covered by every other test of this module, which all use
 * the non-two-phase {@link TestMigratableProcessService}.)
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxStartupValidationTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(TwoPhaseTestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .assertException(exception -> {

        final var stringWriter = new java.io.StringWriter();
        exception.printStackTrace(new java.io.PrintWriter(stringWriter));
        final var failure = stringWriter.toString();

        Assertions.assertTrue(
            failure.contains("requires a two-phase commit"),
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
  public void twoPhaseCommitAdapterWithoutOutboxFailsAtStartupWithRemedies() {
    // the assertException callback holds the assertions
  }

}
