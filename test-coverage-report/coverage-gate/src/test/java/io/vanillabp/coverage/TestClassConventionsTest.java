package io.vanillabp.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.MessageConventions;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.TestClassConventions;

/**
 * A gate in the module which already gates the repository as a whole: every test class
 * registers {@link SuppressOutputExtension}, and every guiding message reads as a
 * sentence.
 * <p>
 * It sits here and not in every module because the checks read sources, so one run
 * covers the whole repository. The first rule had been written down twice and drifted
 * anyway, in nine classes of this repository, which is what a rule enforced by review
 * alone is worth.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TestClassConventionsTest {

  @Test
  @DisplayName("Every test class of this repository suppresses its output")
  public void everyTestClassSuppressesItsOutput() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = TestClassConventions.testClassesWithoutOutputSuppression(root);

    assertTrue(
        offenders.isEmpty(),
        () -> TestClassConventions.describeTestClassesWithoutOutputSuppression(offenders));

  }

  @Test
  @DisplayName("No test class registers the suppression after '@Testcontainers'")
  public void noTestClassSuppressesTooLate() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = TestClassConventions.testClassesSuppressingTooLate(root);

    assertTrue(
        offenders.isEmpty(),
        () -> TestClassConventions.describeTestClassesSuppressingTooLate(offenders));

  }

  @Test
  @DisplayName("No message of this repository carries a run of spaces between two words")
  public void noMessageFellApart() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = MessageConventions.messagesPulledApart(root);

    assertTrue(
        offenders.isEmpty(),
        () -> MessageConventions.describeMessagesPulledApart(offenders));

  }

  @Test
  @DisplayName("No message of this repository glues two words into one")
  public void noMessageIsGluedTogether() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = MessageConventions.messagesGluedTogether(root);

    assertTrue(
        offenders.isEmpty(),
        () -> MessageConventions.describeMessagesGluedTogether(offenders));

  }

}
