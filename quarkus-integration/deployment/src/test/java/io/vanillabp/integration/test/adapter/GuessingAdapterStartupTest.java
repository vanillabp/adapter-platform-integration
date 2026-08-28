package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An adapter which cannot ask its BPMS whether it holds a workflow answers the election
 * optimistically. That is right while it is the only BPMS configured, and a guess as soon
 * as it is not: the walk stops at the first adapter saying yes, so the guessing adapter
 * takes the operations of every adapter behind it in the list.
 * <p>
 * The boot ends there, with the message naming the adapter, the workflow module and the
 * three ways out. The application which wants that routing anyway says so, and gets the
 * WARN instead - that half is
 * {@code AcceptedGuessingAdapterStartupTest}, which needs an application of its own
 * because a Quarkus test boots exactly one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GuessingAdapterStartupTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")
          .addAsResource("guessing-adapter/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addClass(TestAdapterDeploymentService.class)
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class)
          .addClass(Test2ListProcessServiceProducer.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .assertException(exception -> {

        final var stringWriter = new java.io.StringWriter();
        exception.printStackTrace(new java.io.PrintWriter(stringWriter));
        final var failure = stringWriter.toString();

        Assertions.assertTrue(
            failure.contains("cannot ask their BPMS"),
            "expected the guiding startup message but got: "
                + failure);
        Assertions.assertTrue(failure.contains("test-module"), failure);
        // the ways out are part of it
        Assertions.assertTrue(failure.contains("secondary storage"), failure);
        Assertions.assertTrue(
            failure.contains("vanillabp.workflow-modules.test-module.election.guessing-adapters"),
            failure);

      });

  @Test
  @DisplayName("A guessing adapter next to another one ends the boot")
  public void aGuessingAdapterNextToAnotherEndsTheBoot() {
    // the assertException callback holds the assertions
  }

}
