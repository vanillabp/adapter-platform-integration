package io.vanillabp.integration.test.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The one question the coverage gate answers from the command line: does this run reach
 * the phase which writes the reports it judges?
 * <p>
 * Every case which says "no" makes the gate step aside, so the cases which say "yes"
 * are the ones worth writing down. A wrong "no" hides a coverage drop, which is why a
 * command line the gate cannot read counts as a "yes".
 */
@ExtendWith(SuppressOutputExtension.class)
public class CoverageGateCommandLineTest {

  @Test
  @DisplayName("A build which stops at 'package' writes no reports")
  public void aPackageBuildStopsBeforeTheReports() {

    assertTrue(CoverageGate.stopsBeforeTheReportsAreWritten(" package -pl test-utils"));

  }

  @Test
  @DisplayName("The goals which reach 'verify' are recognised, wherever they stand")
  public void theGoalsWhichReachTheReportsAreRecognised() {

    assertFalse(CoverageGate.stopsBeforeTheReportsAreWritten(" verify"));
    assertFalse(CoverageGate.stopsBeforeTheReportsAreWritten(" --batch-mode install"));
    assertFalse(CoverageGate.stopsBeforeTheReportsAreWritten(" clean deploy -DskipITs"));

  }

  @Test
  @DisplayName("A command line the gate cannot read leaves the judging as it was")
  public void anUnreadableCommandLineChangesNothing() {

    // the POM hands over '${env.MAVEN_CMD_LINE_ARGS}', which stays as it stands when
    // Maven was started by something which does not export it
    assertFalse(CoverageGate.stopsBeforeTheReportsAreWritten("${env.MAVEN_CMD_LINE_ARGS}"));
    assertFalse(CoverageGate.stopsBeforeTheReportsAreWritten("   "));
    assertFalse(CoverageGate.stopsBeforeTheReportsAreWritten(null));

  }

  @Test
  @DisplayName("What the gate says names the phase, the run and the way out")
  public void theMessageNamesAllThree() {

    final var message = CoverageGate.describeRunWithoutReports(" package -pl test-utils");

    assertTrue(message.contains("'verify'"), message);
    assertTrue(message.contains("'mvn package -pl test-utils'"), message);
    assertTrue(message.contains("'mvn install'"), message);

  }

}
