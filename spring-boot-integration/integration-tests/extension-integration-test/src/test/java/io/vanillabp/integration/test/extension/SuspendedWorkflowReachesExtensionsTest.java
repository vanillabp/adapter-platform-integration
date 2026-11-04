package io.vanillabp.integration.test.extension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.extension.spi.runtime.SuspendedWorkflowFound;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An adapter which finds a workflow suspended tells the extensions of that adapter, on Spring
 * Boot. The BPMS double stands in for the adapter. It cannot suspend a workflow, so the test
 * makes it report one. Three extensions listen: one which records, one which throws and one
 * written for another BPMS. The Quarkus side holds the same cases in
 * <code>DeploymentPipelineTest</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(classes = {
    TestApplication.class, SuspendedWorkflowReachesExtensionsTest.Listeners.class
})
public class SuspendedWorkflowReachesExtensionsTest {

  /**
   * The extensions of this test.
   */
  @TestConfiguration
  static class Listeners {

    @Bean
    RecordingExtension recordingExtension() {

      return new RecordingExtension();

    }

    @Bean
    ExtensionWiringService<Object, Object> throwingExtension() {

      return new ObjectExtension(-1) {

        @Override
        public void workflowFoundSuspended(
            final SuspendedWorkflowFound found,
            final Object bpmsProcessingContext) {

          throw new IllegalStateException("this extension is broken on purpose");

        }

      };

    }

    @Bean
    ExtensionWiringService<String, String> extensionOfAnotherBpms() {

      return new ExtensionWiringService<>() {

        @Override
        public Class<String> getModelType() {

          return String.class;

        }

        @Override
        public Class<String> getProcessContextType() {

          return String.class;

        }

        @Override
        public void wireBpmn(
            final String workflowModuleId,
            final String filename,
            final String bpmnProcessId,
            final String model,
            final String context) {
          // nothing to wire
        }

        @Override
        public void startWorkflowProcessing(
            final String workflowModuleId,
            final String bpmsProcessingContext) {
          // nothing to start
        }

        @Override
        public void workflowFoundSuspended(
            final SuspendedWorkflowFound found,
            final String bpmsProcessingContext) {

          throw new AssertionError("an extension of another BPMS must not hear about a workflow of the double");

        }

      };

    }

  }

  /**
   * An extension for the BPMS double, which does nothing at the start.
   */
  static class ObjectExtension implements ExtensionWiringService<Object, Object> {

    private final int order;

    ObjectExtension(
        final int order) {

      this.order = order;

    }

    @Override
    public Class<Object> getModelType() {

      return Object.class;

    }

    @Override
    public Class<Object> getProcessContextType() {

      return Object.class;

    }

    @Override
    public int getOrder() {

      return order;

    }

    @Override
    public void wireBpmn(
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final Object model,
        final Object context) {
      // nothing to wire
    }

    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final Object bpmsProcessingContext) {
      // nothing to start
    }

  }

  /**
   * Records what it hears. It runs after the one which throws.
   */
  static class RecordingExtension extends ObjectExtension {

    final List<SuspendedWorkflowFound> heard = new CopyOnWriteArrayList<>();

    RecordingExtension() {

      super(1);

    }

    @Override
    public void workflowFoundSuspended(
        final SuspendedWorkflowFound found,
        final Object bpmsProcessingContext) {

      heard.add(found);

    }

  }

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private RecordingExtension recordingExtension;

  @Test
  @DisplayName("The extensions of the adapter hear it once, and one which throws changes nothing")
  public void theExtensionsOfTheAdapterHearIt() {

    final var adapter = applicationContext
        .getBean("DummyAdapter_DeploymentService_the-bpms", DummyDeploymentService.class);
    final var found = new SuspendedWorkflowFound(
        "the-bpms", "extension-module", "DummyProcess", "1", "4711", "task-1", Instant
            .now(), SuspendedWorkflowFound.Phase.CALL);

    // the throwing extension runs first. The adapter would throw its own exception next, and
    // nothing of the extension may come out instead
    assertDoesNotThrow(() -> adapter.reportSuspendedWorkflow(found));

    // the module is deployed to two adapters, but only the one which found it is told
    assertEquals(List.of(found), recordingExtension.heard);

    // a module this start did not deploy reaches nobody, and fails nothing
    final var elsewhere = new SuspendedWorkflowFound(
        "the-bpms", "no-such-module", null, null, "4712", null, Instant.now(), SuspendedWorkflowFound.Phase.WAITING);
    assertDoesNotThrow(() -> adapter.reportSuspendedWorkflow(elsewhere));
    assertTrue(recordingExtension.heard.stream().noneMatch(heard -> heard.workflowId().equals("4712")));

  }

}
