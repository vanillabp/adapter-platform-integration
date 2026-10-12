package io.vanillabp.integration.adapter.spi.workflowtask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The default of {@link WorkflowTaskWiring#isClaimedByAWorkflowService(String, String)}
 * answers from the name of the aggregate's id property. The core answers from its registry,
 * so only test doubles and other implementations of the SPI reach this default. For them it
 * has to tell an unclaimed process from a claimed one whose persistence has no name to give.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ClaimedByAWorkflowServiceDefaultTest {

  /**
   * Implements only what the SPI demands. The name of the id property comes from the
   * given supplier, which may throw.
   */
  private record WiringWithoutARegistry(Supplier<String> aggregateIdName) implements WorkflowTaskWiring {

    @Override
    public void validateTaskWiring(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<BpmnTaskSpec> tasks) {

    }

    @Override
    public void validateNoUnwiredWorkflowTaskMethods(
        final String workflowModuleId) {

    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return aggregateIdName.get();

    }

  }

  @Test
  @DisplayName("A process with a name for its aggregate id is claimed")
  public void aNameMeansClaimed() {

    final var wiring = new WiringWithoutARegistry(() -> "id");

    assertTrue(wiring.isClaimedByAWorkflowService("module", "Process"));

  }

  @Test
  @DisplayName("A process no workflow service serves is not claimed")
  public void noWorkflowServiceMeansNotClaimed() {

    final var wiring = new WiringWithoutARegistry(() -> {
      throw new IllegalStateException("No @WorkflowService class is registered");
    });

    assertFalse(wiring.isClaimedByAWorkflowService("module", "Process"));

  }

  @Test
  @DisplayName("A claimed process whose persistence has no name for the id is still claimed")
  public void aPersistenceWithoutANameStillMeansClaimed() {

    // allowed for an application on a BPMS with a business key. The persistence is only
    // asked once a workflow service was found for the process
    final var wiring = new WiringWithoutARegistry(() -> {
      throw new UnsupportedOperationException("getAggregateIdName is not implemented");
    });

    assertTrue(wiring.isClaimedByAWorkflowService("module", "Process"));

  }

  @Test
  @DisplayName("Any other failure is not hidden")
  public void anyOtherFailureIsNotHidden() {

    final var wiring = new WiringWithoutARegistry(() -> {
      throw new IllegalArgumentException("something else went wrong");
    });

    assertThrows(
        IllegalArgumentException.class,
        () -> wiring.isClaimedByAWorkflowService("module", "Process"));

  }

}
