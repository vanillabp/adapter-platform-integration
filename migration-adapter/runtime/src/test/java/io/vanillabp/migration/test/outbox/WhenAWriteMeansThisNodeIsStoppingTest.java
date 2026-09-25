package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.outbox.AStoppingNode;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which failures of a store are this node being stopped.
 * <p>
 * The two readings of an exception out of a database driver are far apart: an outbox which
 * cannot reach its database is something to look at, a pod being replaced is not. The
 * stores ask this before they decide how loud to be about a result they could not write
 * down.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WhenAWriteMeansThisNodeIsStoppingTest {

  /**
   * A test which sets the interrupt flag clears it again: the flag belongs to the thread
   * the whole class runs on.
   */
  @AfterEach
  public void clearTheFlag() {

    Thread.interrupted();

  }

  /**
   * The driver's own wrapper, which this module knows by name because it carries no
   * MongoDB dependency.
   */
  private static class MongoInterruptedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MongoInterruptedException(
        final String message) {

      super(message);

    }

  }

  @Test
  @DisplayName("An interrupted thread is a node being stopped, whatever the driver threw")
  public void theInterruptFlagAnswersFirst() {

    Thread.currentThread().interrupt();

    // a driver which turns an interruption into an exception of its own restores the flag
    // while it does so, which is why this is the most reliable of the three answers
    assertTrue(AStoppingNode.isTheReasonFor(new RuntimeException("anything at all")));

  }

  @Test
  @DisplayName("An InterruptedException among the causes is enough")
  public void anInterruptionAmongTheCausesIsEnough() {

    assertTrue(
        AStoppingNode
            .isTheReasonFor(new RuntimeException(
                "Interrupted waiting for lock", new IllegalStateException(new InterruptedException()))));

  }

  @Test
  @DisplayName("An interrupted read of a socket counts as well")
  public void anInterruptedReadCountsAsWell() {

    assertTrue(AStoppingNode.isTheReasonFor(new RuntimeException(new InterruptedIOException())));

  }

  @Test
  @DisplayName("The MongoDB driver's own wrapper is recognized by its name")
  public void theDriversWrapperIsRecognizedByName() {

    assertTrue(
        AStoppingNode
            .isTheReasonFor(new RuntimeException(
                "Uncategorized", new MongoInterruptedException("Interrupted waiting for lock"))));

  }

  @Test
  @DisplayName("A database which is simply gone is no node being stopped")
  public void anOrdinaryFailureIsNotThis() {

    assertFalse(
        AStoppingNode
            .isTheReasonFor(new RuntimeException(
                "Timed out while waiting for a server", new java.net.ConnectException("Connection refused"))));

  }

  @Test
  @DisplayName("An exception which is its own cause ends the walk")
  public void anExceptionCarryingItselfEndsTheWalk() {

    final var itsOwnCause = new RuntimeException("round and round") {

      private static final long serialVersionUID = 1L;

      @Override
      public synchronized Throwable getCause() {

        return this;

      }

    };

    assertFalse(AStoppingNode.isTheReasonFor(itsOwnCause));

  }

}
