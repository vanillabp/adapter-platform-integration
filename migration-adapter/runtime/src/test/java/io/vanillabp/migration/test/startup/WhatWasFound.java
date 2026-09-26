package io.vanillabp.migration.test.startup;

import java.util.List;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.adapter.migration.startup.StartupFindings.Severity;

/**
 * Reads a collection of startup findings the way a test used to read the log.
 * <p>
 * A check no longer writes its own line: it reports, and the whole start says it once at
 * its end. So a test which watched the logger of a check now asks this what the check
 * reported, and it asks in the same words - one entry per finding, the message as a
 * developer reads it.
 */
public final class WhatWasFound {

  private WhatWasFound() {
    // a helper of the tests, never built
  }

  /**
   * Every message reported so far, whatever its severity, in the order it arrived.
   *
   * @param findings Where the checks reported
   * @return The messages
   */
  public static List<String> messages(
      final StartupFindings findings) {

    return findings
        .findings()
        .stream()
        .map(StartupFindings.Finding::message)
        .toList();

  }

  /**
   * The messages of one severity, in the order they arrived.
   *
   * @param findings Where the checks reported
   * @param severity How loud the wanted findings are
   * @return The messages
   */
  public static List<String> messages(
      final StartupFindings findings,
      final Severity severity) {

    return findings
        .findings()
        .stream()
        .filter(finding -> finding.severity() == severity)
        .map(StartupFindings.Finding::message)
        .toList();

  }

  /**
   * Every finding reported so far, each as the scope it is about plus its message - what
   * a test reads where the scope carries half of what the check said.
   *
   * @param findings Where the checks reported
   * @return One entry per finding
   */
  public static List<String> entries(
      final StartupFindings findings) {

    return findings
        .findings()
        .stream()
        .map(finding -> finding.scope() == null
            ? finding.message()
            : "%s: %s".formatted(finding.scope(), finding.message()))
        .toList();

  }

  /**
   * Every message reported so far, each with the scope it is about, as one text - what a
   * test asserts a phrase against.
   *
   * @param findings Where the checks reported
   * @return The messages, one per line block
   */
  public static String text(
      final StartupFindings findings) {

    return findings
        .findings()
        .stream()
        .map(finding -> finding.scope() == null
            ? finding.message()
            : "%s: %s".formatted(finding.scope(), finding.message()))
        .collect(Collectors.joining("\n"));

  }

}
