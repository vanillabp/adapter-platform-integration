package io.vanillabp.integration.test.processservice;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentService;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer;
import io.vanillabp.integration.test.adapter.TestMigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Startup check of story 40b on Quarkus: a <code>&#64;WorkflowTask</code> handler
 * covered by a transaction annotation of the application would lose every change made
 * to the workflow aggregate as soon as a {@code TaskException} passes the annotation's
 * interceptor, and the rollback-only mark it sets cannot be cleared. So the boot fails,
 * naming the class, the method and both remedies.
 * <p>
 * The accepted counterpart is {@link AcceptedTransactionAnnotationStartupTest}; the
 * matrix of annotations, propagations and rollback rules is covered by the core's unit
 * tests.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TransactionAnnotationStartupTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.transactional")
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(TestAdapterDeploymentService.class)
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .assertException(exception -> {

        final var stringWriter = new java.io.StringWriter();
        exception.printStackTrace(new java.io.PrintWriter(stringWriter));
        final var failure = stringWriter.toString();

        Assertions.assertTrue(
            failure.contains("covered by a transaction annotation of the application"),
            "expected the guiding startup message but got: "
                + failure);
        Assertions.assertTrue(
            failure.contains("io.vanillabp.integration.test.samples.transactional.TransactionalWorkflowService"),
            "the offending class is not named: "
                + failure);
        Assertions.assertTrue(failure.contains("#assessRisk"), "the offending method is not named: "
            + failure);
        Assertions.assertTrue(
            failure.contains("jakarta.transaction.Transactional"),
            "the offending annotation is not named: "
                + failure);
        // the remedy of the OFFENDING annotation, which on Quarkus is the JTA one
        Assertions.assertTrue(
            failure.contains("dontRollbackOn = TaskException.class"),
            "the remedy is missing: "
                + failure);
        Assertions.assertTrue(
            failure.contains("remove the annotation from the workflow task method"),
            "the remedy is missing: "
                + failure);

      });

  /**
   * The startup has to fail - the assertion happens on the boot failure above.
   */
  @Test
  public void bootFails() {

    Assertions.fail("the application must not boot with a transaction annotation on a @WorkflowTask method");

  }

}
