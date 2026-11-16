package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoCollision;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The router repeats a dispatch which lost a race against another change of the same
 * workflow, a few times and at once. The store never hears of a repetition which wins. The
 * outbox-level tests of both platforms show what that means for an entry. These tests hold
 * the edges: the bound, the failure the store gets in the end, and the transaction which
 * makes a repetition impossible.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
public class ACollisionIsRepeatedInsideTheDispatchTest {

  private static final PhaseTwoCall CALL = PhaseTwoCall
      .of(PhaseOperation.START_WORKFLOW, "test-module", "TestProcess", "42", "test-adapter", Map.of());

  @Mock
  private MigrationProcessService<Object> processService;

  private final StoreTransaction runner = new StoreTransaction();

  private final PhaseTwoRouter testee = new PhaseTwoRouter(runner);

  @BeforeEach
  public void registerProcessService() {

    when(processService.getWorkflowModuleId()).thenReturn("test-module");
    when(processService.getBpmnProcessId()).thenReturn("TestProcess");
    when(processService.convertAggregateId("42")).thenReturn(42L);
    testee.register(processService);

  }

  private void dispatchFails(
      final RuntimeException... failures) {

    var stubbing = Mockito.doThrow(failures[0]);
    for (var index = 1; index < failures.length; ++index) {
      stubbing = stubbing.doThrow(failures[index]);
    }
    stubbing
        .doNothing()
        .when(processService)
        .executePhaseTwo(PhaseOperation.START_WORKFLOW, 42L, "test-adapter", Map.of(), false);

  }

  private void dispatchedTimes(
      final int times) {

    verify(processService, times(times))
        .executePhaseTwo(PhaseOperation.START_WORKFLOW, 42L, "test-adapter", Map.of(), false);

  }

  private static PhaseTwoCollision aLostRace(
      final RuntimeException failureOfTheAdapter) {

    return new PhaseTwoCollision("lost a race for testing purposes", failureOfTheAdapter);

  }

  @Test
  @DisplayName("A lost race is repeated in a transaction of its own, and the store never hears of it")
  public void aLostRaceIsRepeated() {

    dispatchFails(aLostRace(new IllegalStateException("the row changed")));

    testee.dispatch(CALL);

    dispatchedTimes(2);
    if (runner.transactions != 2) {
      throw new AssertionError("each attempt needs a transaction of its own, but got "
          + runner.transactions);
    }

  }

  @Test
  @DisplayName("The repetitions are bounded, and the store gets the failure of the adapter unwrapped")
  public void theRepetitionsAreBounded() {

    final var failureOfTheAdapter = new IllegalStateException("the row changed again");
    final var races = new RuntimeException[PhaseTwoRouter.PAUSES_BEFORE_REPEATING_A_COLLISION.size() + 2];
    java.util.Arrays.fill(races, aLostRace(failureOfTheAdapter));
    dispatchFails(races);

    final var thrown = assertThrowsExactly(IllegalStateException.class, () -> testee.dispatch(CALL));

    // the store sees what it saw before races were told apart, and counts one attempt
    assertSame(failureOfTheAdapter, thrown);
    dispatchedTimes(1 + PhaseTwoRouter.PAUSES_BEFORE_REPEATING_A_COLLISION.size());

  }

  @Test
  @DisplayName("Inside a transaction of the store a lost race is not repeated, because that transaction cannot commit any more")
  public void insideATransactionOfTheStoreNothingIsRepeated() {

    final var failureOfTheAdapter = new IllegalStateException("the row changed");
    dispatchFails(aLostRace(failureOfTheAdapter));
    runner.openedByTheStore = true;

    assertSame(failureOfTheAdapter, assertThrowsExactly(IllegalStateException.class, () -> testee.dispatch(CALL)));
    dispatchedTimes(1);

  }

  @Test
  @DisplayName("Any other failure goes to the store at once")
  public void anyOtherFailureIsNotRepeated() {

    final var outage = new IllegalStateException("the database is away");
    dispatchFails(outage);

    assertSame(outage, assertThrowsExactly(IllegalStateException.class, () -> testee.dispatch(CALL)));
    dispatchedTimes(1);

  }

  @Test
  @DisplayName("A stopping node ends the repetitions and hands the failure to the store")
  public void aStoppingNodeEndsTheRepetitions() {

    final var failureOfTheAdapter = new IllegalStateException("the row changed");
    dispatchFails(aLostRace(failureOfTheAdapter));

    Thread.currentThread().interrupt();
    try {
      assertSame(failureOfTheAdapter, assertThrowsExactly(IllegalStateException.class, () -> testee.dispatch(CALL)));
      if (!Thread.interrupted()) {
        throw new AssertionError("the interruption was swallowed");
      }
    } finally {
      Thread.interrupted();
    }
    dispatchedTimes(1);

  }

  /**
   * Plays the transaction runner of a platform. It counts the transactions it opened, and it
   * can play a store which dispatches inside a transaction it opened itself.
   */
  private static class StoreTransaction implements TransactionRunner {

    private boolean openedByTheStore;

    private int transactions;

    @Override
    public <T> T requireTransaction(
        final Supplier<T> work) {

      transactions += 1;
      return work.get();

    }

    @Override
    public boolean isTransactionActive() {

      return openedByTheStore;

    }

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {

      throw new UnsupportedOperationException("phase two has to use requireTransaction");

    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {

      throw new UnsupportedOperationException("phase two has to use requireTransaction");

    }

    @Override
    public boolean isRollbackOnly() {

      return false;

    }

    @Override
    public boolean isConcurrentModification(
        final Throwable failure) {

      return false;

    }

  }

}
