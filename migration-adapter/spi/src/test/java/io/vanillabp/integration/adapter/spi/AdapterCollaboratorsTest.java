package io.vanillabp.integration.adapter.spi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a complete registration is, asserted where it is decided: the platform hands its
 * collaborators to an adapter in one object, and that object is the place which knows
 * which of them every adapter gets and which an application may not have.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AdapterCollaboratorsTest {

  // the collaborators themselves are never called here - what this test pins is which of
  // them have to arrive, so mocks say that without a page of empty method bodies
  private static final WorkflowTaskWiring WIRING = mock(WorkflowTaskWiring.class);

  private static final WorkflowTaskInvoker INVOKER = mock(WorkflowTaskInvoker.class);

  private static final NameClashAvoidanceSupport SCOPING = mock(NameClashAvoidanceSupport.class);

  private static final WorkflowAggregateSync AGGREGATE_SYNC = mock(WorkflowAggregateSync.class);

  private static final PreCommitRegistrar PRE_COMMIT = mock(PreCommitRegistrar.class);

  private static AdapterCollaborators.Builder complete() {

    return AdapterCollaborators
        .forAdapter("dummy")
        .workflowTaskWiring(WIRING)
        .workflowTaskInvoker(INVOKER)
        .scoping(SCOPING)
        .workflowAggregateSync(AGGREGATE_SYNC)
        .preCommitRegistrar(PRE_COMMIT);

  }

  @Test
  @DisplayName("A complete set hands every collaborator through")
  public void aCompleteSetIsBuilt() {

    final var collaborators = complete().build();

    assertSame(WIRING, collaborators.workflowTaskWiring());
    assertSame(INVOKER, collaborators.workflowTaskInvoker());
    assertSame(SCOPING, collaborators.scoping());
    assertSame(AGGREGATE_SYNC, collaborators.workflowAggregateSync());
    assertSame(PRE_COMMIT, collaborators.preCommitRegistrar());

  }

  @Test
  @DisplayName("A missing mandatory collaborator ends the build naming the adapter and the collaborator")
  public void aMissingMandatoryCollaboratorIsRefused() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> AdapterCollaborators
            .forAdapter("c8")
            .workflowTaskWiring(WIRING)
            .workflowTaskInvoker(INVOKER)
            .scoping(SCOPING)
            // the aggregate sync and the pre-commit registrar are the ones forgotten here
            .build());

    assertTrue(failure.getMessage().contains("'c8'"), failure::getMessage);
    assertTrue(failure.getMessage().contains("'workflowAggregateSync'"), failure::getMessage);
    assertTrue(failure.getMessage().contains("'preCommitRegistrar'"), failure::getMessage);
    // and it says whose defect it is, because no application can configure this away
    assertTrue(failure.getMessage().contains("registration code"), failure::getMessage);

  }

  @Test
  @DisplayName("An optional collaborator which did not arrive is named at build time")
  public void anAbsentOptionalCollaboratorIsReported(
      final CapturedOutput output) {

    complete().workflowEndedInvoker(null).bpmsInitiatedStartInvoker(null).build();

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("'dummy'"), "the line names the adapter id: "
        + logged);
    assertTrue(logged.contains("workflowEndedInvoker"), "and what did not arrive: "
        + logged);
    assertTrue(logged.contains("bpmsInitiatedStartInvoker"), "both of them: "
        + logged);

  }

  @Test
  @DisplayName("A set with both optional collaborators says nothing")
  public void aCompleteSetIsSilent(
      final CapturedOutput output) {

    final var before = output.getAll().length();
    complete()
        .workflowEndedInvoker(mock(WorkflowEndedInvoker.class))
        .bpmsInitiatedStartInvoker(null)
        .build();

    assertTrue(
        output.getAll().substring(before).contains("bpmsInitiatedStartInvoker"),
        "the one which is missing is still named");
    assertFalse(
        output.getAll().substring(before).contains("and 'workflowEndedInvoker'"),
        "the one which arrived is not");

  }

}
