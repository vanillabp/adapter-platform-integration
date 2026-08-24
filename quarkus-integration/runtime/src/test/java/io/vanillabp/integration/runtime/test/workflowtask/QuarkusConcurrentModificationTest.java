package io.vanillabp.integration.runtime.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.runtime.workflowtask.QuarkusTransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which failures Quarkus reports as "somebody else changed the workflow aggregate".
 * Under JTA the conflict arrives wrapped twice, so what is asserted here is
 * the walk along the causes; no transaction is needed for it, which is why the runner is
 * built without a registry.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusConcurrentModificationTest {

  private final QuarkusTransactionRunner runner = new QuarkusTransactionRunner(null);

  @Test
  @DisplayName("The conflict is found however deep JTA wrapped it")
  public void wrappedOptimisticLockExceptionsAreRecognized() {

    assertTrue(runner.isConcurrentModification(new jakarta.persistence.OptimisticLockException("Row 4711")));
    assertTrue(
        runner
            .isConcurrentModification(
                new RuntimeException(
                    "transaction failed", new jakarta.transaction.RollbackException(
                        "rolled back").initCause(new jakarta.persistence.OptimisticLockException("Row 4711")))));

  }

  @Test
  @DisplayName("Everything else is somebody else's failure")
  public void otherFailuresAreNotConflicts() {

    assertFalse(runner.isConcurrentModification(new IllegalStateException("the handler threw")));
    assertFalse(runner.isConcurrentModification(null));

  }

}
