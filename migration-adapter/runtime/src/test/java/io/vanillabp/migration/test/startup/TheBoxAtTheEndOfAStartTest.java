package io.vanillabp.migration.test.startup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.adapter.migration.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a start says about itself when it is over.
 * <p>
 * Every word of the box is asserted here, the shape included: the two rulers, the head
 * line, the headings with their counts, and the fact that a start with nothing to say
 * writes nothing at all - no ruler, no heading, no empty message. The shape is the
 * feature, so it is held by tests like any other promise.
 * <p>
 * The box goes into the log in ONE call, which is the other thing asserted here: written
 * line by line, the INFO line of the next library would land in the middle of it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheBoxAtTheEndOfAStartTest {

  private static final String RULER = "-".repeat(102);

  private StartupFindings findings;

  private ListAppender<ILoggingEvent> logWatcher;

  @BeforeEach
  public void watchTheLog() {

    findings = new StartupFindings();
    logWatcher = new ListAppender<>();
    logWatcher.start();
    ((Logger) LoggerFactory.getLogger(StartupFindings.class)).addAppender(logWatcher);

  }

  @AfterEach
  public void stopWatchingTheLog() {

    ((Logger) LoggerFactory.getLogger(StartupFindings.class)).detachAndStopAllAppenders();

  }

  @Test
  @DisplayName("A start with nothing to say writes nothing at all")
  public void nothingToSayIsNothingWritten() {

    assertTrue(findings.nothingToSay());
    assertNull(findings.theBox());
    assertNull(findings.theRefusal());

    assertDoesNotThrow(() -> findings.endOfStartup());

    assertEquals(0, logWatcher.list.size(), "a healthy start reads the way it did before");

  }

  @Test
  @DisplayName("One finding is one box, between two rulers and in one logging call")
  public void oneFindingIsOneBox() {

    findings
        .warn(
            StartupTopic.CONFIGURATION,
            "vanillabp.outbox.housekeeping",
            "The window runs at an hour nobody meant. Say which zone you mean.");

    findings.endOfStartup();

    assertEquals(1, logWatcher.list.size(), "one call, so no other library's line falls into it");
    assertEquals(Level.WARN, logWatcher.list.get(0).getLevel());
    assertEquals(
        """
            %s
            VanillaBP 2.0 looked at this application and found 1 thing worth a look. None of them stopped the start.

            CONFIGURATION (1)
              vanillabp.outbox.housekeeping
                  The window runs at an hour nobody meant. Say which zone you mean.
            %s"""
            .formatted(RULER, RULER),
        logWatcher.list.get(0).getFormattedMessage());

  }

  @Test
  @DisplayName("The topics stand in the order a developer walks them, each with its count")
  public void theTopicsAreCountedAndOrdered() {

    findings.warn(StartupTopic.DEPLOYED_VERSIONS, "process 'loan-approval'", "Version 2 is served by nobody.");
    findings.warn(StartupTopic.CONFIGURATION, "vanillabp.workflow-modules.loan", "This module is on no class path.");
    findings.warn(StartupTopic.CODE, "aggregate 'io.example.Loan'", "It has no version attribute.");
    findings.warn(StartupTopic.CONFIGURATION, "vanillabp.adapters.cloud", "The request timeout is half a second.");

    assertEquals(
        """
            %s
            VanillaBP 2.0 looked at this application and found 4 things worth a look. None of them stopped the start.

            CONFIGURATION (2)
              vanillabp.workflow-modules.loan
                  This module is on no class path.
              vanillabp.adapters.cloud
                  The request timeout is half a second.

            CODE (1)
              aggregate 'io.example.Loan'
                  It has no version attribute.

            DEPLOYED VERSIONS (1)
              process 'loan-approval'
                  Version 2 is served by nobody.
            %s"""
            .formatted(RULER, RULER),
        findings.theBox());

  }

  @Test
  @DisplayName("The same finding over twelve modules is one entry naming twelve modules")
  public void onefindingOverManyScopesIsFolded() {

    for (final var module : new String[]{
        "orders", "payments", "shipping"
    }) {
      findings
          .warn(
              StartupTopic.STORED_STATE,
              "workflow module '%s'".formatted(module),
              "No store remembers the deliveries of this BPMS.");
    }

    final var box = findings.theBox();

    assertTrue(box.contains("STORED STATE (1)"), box);
    assertTrue(
        box.contains("  workflow module 'orders', workflow module 'payments', workflow module 'shipping'"),
        box);
    assertTrue(box.contains("found 1 thing worth a look"), box);

  }

  @Test
  @DisplayName("A finding reported twice about the same scope is one finding")
  public void theSameFindingTwiceIsOne() {

    findings.warn(StartupTopic.CODE, "class 'io.example.Loans'", "Nothing serves this method.");
    findings.warn(StartupTopic.CODE, "class 'io.example.Loans'", "Nothing serves this method.");

    assertEquals(1, findings.findings().size());
    assertTrue(findings.theBox().contains("CODE (1)"), findings.theBox());

  }

  @Test
  @DisplayName("What costs more is read first")
  public void theLoudestOfATopicComesFirst() {

    findings.notice(StartupTopic.INFRASTRUCTURE, "the database", "The table was created for you.");
    findings.error(StartupTopic.INFRASTRUCTURE, "the cluster", "This node is alone.");
    findings.warn(StartupTopic.INFRASTRUCTURE, "the replica set", "MongoDB runs without one.");

    final var box = findings.theBox();

    assertTrue(
        box.indexOf("This node is alone.") < box.indexOf("MongoDB runs without one."),
        box);
    assertTrue(
        box.indexOf("MongoDB runs without one.") < box.indexOf("The table was created for you."),
        box);

  }

  @Test
  @DisplayName("A box of notices alone is written at INFO")
  public void noticesAloneAreNotAWarning() {

    findings.notice(StartupTopic.PARTS_AND_VERSIONS, "vanillabp-core", "This release line serves 8.8.");

    findings.endOfStartup();

    assertEquals(Level.INFO, logWatcher.list.get(0).getLevel());

  }

  @Test
  @DisplayName("Every line of a message keeps its place under the scope")
  public void aMessageWithSeveralLinesKeepsItsShape() {

    findings
        .warn(
            StartupTopic.CONFIGURATION,
            "vanillabp.delivery",
            """
                Two keys say the same thing:
                  - vanillabp.delivery.retention
                  - vanillabp.outbox.retention""");

    assertEquals(
        """
            %s
            VanillaBP 2.0 looked at this application and found 1 thing worth a look. None of them stopped the start.

            CONFIGURATION (1)
              vanillabp.delivery
                  Two keys say the same thing:
                    - vanillabp.delivery.retention
                    - vanillabp.outbox.retention
            %s"""
            .formatted(RULER, RULER),
        findings.theBox());

  }

  @Test
  @DisplayName("What was refused is thrown once, grouped the way the box is grouped")
  public void everyRefusalIsThrownAtOnce() {

    findings.refuse(StartupTopic.CONFIGURATION, "vanillabp.adapters.cloud", "This adapter has no type.");
    findings.refuse(StartupTopic.CODE, "class 'io.example.Loans'", "Two methods serve version 3.");

    final var refused = assertThrows(IllegalStateException.class, () -> findings.endOfStartup());

    assertEquals(
        """
            %s
            VanillaBP 2.0 cannot start this application: 2 things have to change.

            CONFIGURATION (1)
              vanillabp.adapters.cloud
                  This adapter has no type.

            CODE (1)
              class 'io.example.Loans'
                  Two methods serve version 3.
            %s"""
            .formatted(RULER, RULER),
        refused.getMessage());

  }

  @Test
  @DisplayName("One refusal reads as one thing, not as one of many")
  public void oneRefusalIsSaidInTheSingular() {

    findings.refuse(StartupTopic.CONFIGURATION, "vanillabp.adapters.cloud", "This adapter has no type.");

    assertTrue(
        findings
            .theRefusal()
            .getMessage()
            .contains("VanillaBP 2.0 cannot start this application: one thing has to change."),
        findings.theRefusal().getMessage());

  }

  @Test
  @DisplayName("A warning does not disappear because the start later fails")
  public void aWarningSurvivesARefusal() {

    findings.warn(StartupTopic.CONFIGURATION, "vanillabp.outbox.housekeeping", "The window runs at four UTC.");
    findings.refuse(StartupTopic.CODE, "class 'io.example.Loans'", "Two methods serve version 3.");

    assertThrows(IllegalStateException.class, () -> findings.endOfStartup());

    assertEquals(1, logWatcher.list.size());
    final var box = logWatcher.list.get(0).getFormattedMessage();
    assertTrue(box.contains("The window runs at four UTC."), box);
    // and the box says why there is more to read below it
    assertTrue(box.contains("The start ends after them, see the refusal below."), box);
    // what ends the start is not repeated among the things which did not
    assertTrue(!box.contains("Two methods serve version 3."), box);

  }

  @Test
  @DisplayName("A refusal without anything else writes no box")
  public void aRefusalAloneIsNoBox() {

    findings.refuse(StartupTopic.CODE, "class 'io.example.Loans'", "Two methods serve version 3.");

    assertThrows(IllegalStateException.class, () -> findings.endOfStartup());

    assertEquals(0, logWatcher.list.size(), "there is nothing the start survived to tell about");

  }

  @Test
  @DisplayName("A start ends once, however often it is asked to")
  public void theEndOfAStartHappensOnce() {

    findings.warn(StartupTopic.CONFIGURATION, "vanillabp.outbox.housekeeping", "The window runs at four UTC.");

    findings.endOfStartup();
    findings.endOfStartup();

    assertEquals(1, logWatcher.list.size());

  }

  @Test
  @DisplayName("A start which ends on something else still writes what was found")
  public void aStartWhichFailsElsewhereStillSaysWhatItFound() {

    findings.warn(StartupTopic.CONFIGURATION, "vanillabp.outbox.housekeeping", "The window runs at four UTC.");
    findings.refuse(StartupTopic.CODE, "class 'io.example.Loans'", "Two methods serve version 3.");

    assertDoesNotThrow(() -> findings.sayWhatWasFoundBeforeTheStartFailed());

    assertEquals(1, logWatcher.list.size());
    final var box = logWatcher.list.get(0).getFormattedMessage();
    assertTrue(box.contains("The window runs at four UTC."), box);
    // the refusal is part of the block here: it will never be thrown, and the exception
    // ending this start is the one the developer is about to read
    assertTrue(box.contains("Two methods serve version 3."), box);
    assertTrue(box.contains("The start ended on something else"), box);

  }

  @Test
  @DisplayName("Nothing found and a failed start is still no box")
  public void aFailedStartWithNothingFoundIsSilent() {

    findings.sayWhatWasFoundBeforeTheStartFailed();

    assertEquals(0, logWatcher.list.size());

  }

  @Test
  @DisplayName("A finding which arrives after the box goes into the log where it was found")
  public void aLateFindingIsLoggedWhereItWasFound() {

    findings.endOfStartup();

    findings.warn(StartupTopic.STORED_STATE, "adapter id 'old-bpms'", "An entry still waits for it.");

    assertEquals(1, logWatcher.list.size());
    final var line = logWatcher.list.get(0).getFormattedMessage();
    assertTrue(line.contains("adapter id 'old-bpms'"), line);
    assertTrue(line.contains("An entry still waits for it."), line);
    assertTrue(findings.findings().isEmpty(), "a box which was written collects nothing more");

  }

  @Test
  @DisplayName("A refusal which arrives too late is written, not thrown")
  public void aLateRefusalDoesNotEndARunningApplication() {

    findings.endOfStartup();

    assertDoesNotThrow(
        () -> findings.refuse(StartupTopic.CODE, "class 'io.example.Loans'", "Two methods serve version 3."));

    assertEquals(1, logWatcher.list.size());
    assertEquals(
        Level.ERROR,
        logWatcher.list.get(0).getLevel(),
        "the start it should have stopped is over, so it is as loud as a log line can be");

  }

  @Test
  @DisplayName("A collected reason not to start is known before the box is written")
  public void aRefusalIsKnownBeforeTheEnd() {

    assertFalse(findings.somethingWasRefused());

    findings.warn(StartupTopic.CONFIGURATION, "vanillabp.outbox.housekeeping", "The window runs at four UTC.");
    assertFalse(findings.somethingWasRefused(), "a warning is no reason not to start");

    findings.refuse(StartupTopic.CODE, "class 'io.example.Loans'", "Two methods serve version 3.");
    assertTrue(findings.somethingWasRefused());

  }

}
