package io.vanillabp.integration.runtime.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Instance;

/**
 * What a producer gets handed, and what it is told when a collaborator did not resolve.
 * The Spring Boot side pins the same two cases against its own helper: an adapter
 * supporting both platforms has to be registered completely on both, and neither
 * platform may be the one where a forgotten collaborator stays quiet.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AdapterCollaboratorsSupportTest {

  @SuppressWarnings("unchecked")
  private static <T> Instance<T> resolving(
      final Class<T> type) {

    final var instance = (Instance<T>) mock(Instance.class);
    when(instance.isResolvable()).thenReturn(Boolean.TRUE);
    when(instance.get()).thenReturn(mock(type));
    return instance;

  }

  /**
   * An {@code Instance} of a bean nobody produced - what CDI hands a producer whose
   * application has no method for the thing.
   */
  @SuppressWarnings("unchecked")
  private static <T> Instance<T> unsatisfied() {

    final var instance = (Instance<T>) mock(Instance.class);
    when(instance.isResolvable()).thenReturn(Boolean.FALSE);
    return instance;

  }

  private static io.vanillabp.integration.adapter.spi.AdapterCollaborators collaborators(
      final String adapterId,
      final Instance<WorkflowEndedInvoker> workflowEndedInvoker,
      final Instance<BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker) {

    return AdapterCollaboratorsSupport
        .collaborators(
            adapterId, mock(WorkflowTaskWiring.class), mock(WorkflowTaskInvoker.class),
            mock(NameClashAvoidanceSupport.class), mock(WorkflowAggregateSync.class), mock(PreCommitRegistrar.class),
            workflowEndedInvoker, bpmsInitiatedStartInvoker);

  }

  @Test
  @DisplayName("A complete producer yields every collaborator")
  public void aCompleteProducerYieldsEverything() {

    final var collaborators = collaborators(
        "c8",
        resolving(WorkflowEndedInvoker.class),
        resolving(BpmsInitiatedStartInvoker.class));

    assertEquals("c8", collaborators.adapterId());
    assertNotNull(collaborators.workflowTaskWiring());
    assertNotNull(collaborators.workflowTaskInvoker());
    assertNotNull(collaborators.scoping());
    assertNotNull(collaborators.workflowAggregateSync());
    assertNotNull(collaborators.preCommitRegistrar());
    assertTrue(collaborators.workflowEndedInvoker().isPresent());
    assertTrue(collaborators.bpmsInitiatedStartInvoker().isPresent());

  }

  @Test
  @DisplayName("A collaborator which did not resolve arrives as absent")
  public void anUnresolvedCollaboratorIsAbsent() {

    final var collaborators = collaborators(
        "dummy",
        resolving(WorkflowEndedInvoker.class),
        unsatisfied());

    assertTrue(
        collaborators.bpmsInitiatedStartInvoker().isEmpty(),
        "the adapter reports no workflow started by the BPMS");
    assertTrue(collaborators.workflowEndedInvoker().isPresent(), "the other one arrived");
    // that the absence is also reported with the adapter id is pinned where the line is
    // written, in AdapterCollaboratorsTest of the SPI - the log of this module goes
    // through the JBoss log manager Quarkus installs, which the capture does not see

  }

  @Test
  @DisplayName("A producer which passes no Instance at all is treated as an absent bean")
  public void aMissingInstanceIsTreatedAsAbsent() {

    // a producer written without the parameter: CDI hands nothing, and the adapter is
    // built without the collaborator rather than failing on a null
    final var collaborators = collaborators("dummy", null, null);

    assertTrue(collaborators.workflowEndedInvoker().isEmpty());
    assertTrue(collaborators.bpmsInitiatedStartInvoker().isEmpty());

  }

}
