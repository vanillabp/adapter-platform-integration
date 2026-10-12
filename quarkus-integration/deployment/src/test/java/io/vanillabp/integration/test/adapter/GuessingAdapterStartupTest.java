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
 * Some adapters cannot ask their BPMS whether it holds a workflow. Such an adapter answers
 * the election with yes. That is right while it is the only BPMS configured. As soon as it
 * is not, it is a guess. The election stops at the first adapter which says yes. So the
 * guessing adapter takes the operations of every adapter behind it in the list.
 * <p>
 * So the boot ends there. The message names the adapter, the workflow module and the three
 * ways out. An application which wants that routing anyway says so, and gets the WARN
 * instead. That half is {@code AcceptedGuessingAdapterStartupTest}. It needs an application
 * of its own, because a Quarkus test boots exactly one.
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
        // the adapter which guesses is 'test'. test-adapter.can-locate-workflows is false
        Assertions.assertTrue(failure.contains("The adapter(s) [test] cannot ask"), failure);
        Assertions.assertTrue(failure.contains("test-module"), failure);
        // the message contains the three ways out
        Assertions.assertTrue(failure.contains("secondary storage"), failure);
        Assertions.assertTrue(failure.contains("prioritize exactly one adapter"), failure);
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
