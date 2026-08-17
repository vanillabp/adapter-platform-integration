package io.vanillabp.integration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowtask.SpringTransactionRunner;

/**
 * Which failures Spring Boot reports as "somebody else changed the workflow aggregate"
 * (story 59). The transaction runner is asked without any transaction manager, since
 * the classification looks at the exception alone.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SpringConcurrentModificationTest {

  private final SpringTransactionRunner runner = new SpringTransactionRunner(
      (org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager>) null);

  @Test
  @DisplayName("Spring's own translation is recognized, wrapped as well as bare")
  public void springsTranslationIsRecognized() {

    assertTrue(runner.isConcurrentModification(new ObjectOptimisticLockingFailureException("Aggregate", 4711L)));
    // MongoDB reports the base type rather than the JPA-flavored subclass
    assertTrue(runner.isConcurrentModification(new OptimisticLockingFailureException("version 3 expected")));
    assertTrue(
        runner
            .isConcurrentModification(
                new IllegalStateException("commit failed", new OptimisticLockingFailureException("stale"))));

  }

  @Test
  @DisplayName("A provider exception which never passed Spring's translation counts too")
  public void untranslatedProviderExceptionsCount() {

    assertTrue(
        runner
            .isConcurrentModification(
                new IllegalStateException(
                    "commit failed", new jakarta.persistence.OptimisticLockException("Row 4711"))));

  }

  @Test
  @DisplayName("Everything else is somebody else's failure")
  public void otherFailuresAreNotConflicts() {

    assertFalse(runner.isConcurrentModification(new IllegalStateException("the handler threw")));
    assertFalse(runner.isConcurrentModification(null));

  }

  @Test
  @DisplayName("A self-referencing cause does not spin")
  public void aSelfReferencingCauseTerminates() {

    final var selfReferencing = new RuntimeException("looping") {

      @Override
      public synchronized Throwable getCause() {
        return this;
      }

    };

    assertFalse(runner.isConcurrentModification(selfReferencing));

  }

}
