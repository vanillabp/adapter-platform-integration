package io.vanillabp.integration.adapter.migration.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;

/**
 * The text which an outbox entry keeps about the last attempt that did not get through.
 * That is the column <code>LAST_FAILURE</code> of the JDBC table, and the field
 * <code>lastFailure</code> of the MongoDB collection. Every store builds the text here, so a
 * blocked entry reads the same on every store.
 * <p>
 * The text is the class and the message of what was thrown, and of each cause which adds
 * something, joined into one line. The stack trace is not part of it. It stays in the log,
 * where the same attempt wrote it.
 * <p>
 * The text is cut to {@link #MAX_BYTES} bytes of UTF-8. Bytes and not characters, because
 * Oracle and DB2 count a <code>VARCHAR</code> in bytes by default. Otherwise a message with
 * an umlaut in it would be refused by exactly the write which blocks the entry. See decision
 * 118 in the repository's DECISIONS.md.
 */
public final class LastFailure {

  /**
   * How long the text may be, in bytes of UTF-8. It is the length of the column as well.
   */
  public static final int MAX_BYTES = 1000;

  /**
   * What a text which was cut ends with, so that a reader knows there was more.
   */
  static final String CUT_MARK = "...";

  /**
   * What stands between a failure and its cause.
   */
  static final String CAUSED_BY = "; caused by ";

  /**
   * Line breaks and the blanks around them. A message with several lines is written as one
   * line, because the query which reads the column prints one row per line.
   */
  private static final Pattern LINE_BREAKS = Pattern.compile("\\s*[\\r\\n]+\\s*");

  private LastFailure() {
  }

  /**
   * The text an entry keeps about a dispatch which threw.
   *
   * @param failure What the dispatch threw
   * @return Class and message of the failure and of each cause which adds something, at
   *         most {@link #MAX_BYTES} bytes long
   */
  public static String of(
      final Throwable failure) {

    return cut(describe(failure));

  }

  /**
   * The text which an entry keeps when it is blocked because its BPMS did not report the
   * workflow in time. What was thrown is only the last "not yet". So the text first says why
   * the entry was given up.
   *
   * @param waited How long the entry waited since it was written
   * @param lastAnswer The last "not yet" of the adapter
   * @return The text, at most {@link #MAX_BYTES} bytes long
   */
  public static String ofAWaitWhichRanOut(
      final Duration waited,
      final Throwable lastAnswer) {

    return cut(
        "Waited %s for the BPMS to report the workflow, which is longer than '%s' allows. The last answer was: %s"
            .formatted(waited, PhaseTwoOutboxProperties.WAIT_FOR_VISIBILITY_AT_MOST_PROPERTY, describe(lastAnswer)));

  }

  /**
   * The reason of an entry whose wait passed the deadline its adapter named.
   *
   * @param waited How long the entry waited since it was written
   * @param deadline The deadline the adapter named
   * @param lastAnswer The last "not yet" of the adapter
   * @return The text, at most {@link #MAX_BYTES} bytes long
   */
  public static String ofAWaitPastItsDeadline(
      final Duration waited,
      final java.time.Instant deadline,
      final Throwable lastAnswer) {

    return cut(
        "Waited %s, which passed the deadline %s the adapter named. The last answer was: %s"
            .formatted(waited, deadline, describe(lastAnswer)));

  }

  /**
   * Class and message of the failure and of its causes. A cause is left out where the text
   * before it already contains it. <code>new RuntimeException(cause)</code> does that,
   * because its message is the cause's own <code>toString()</code>.
   *
   * @param failure What was thrown
   * @return The whole text, not cut yet
   */
  private static String describe(
      final Throwable failure) {

    final var parts = new ArrayList<String>();
    final var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    var current = failure;
    while ((current != null) && seen.add(current)) {
      final var text = oneLine(current.toString());
      if (parts.isEmpty() || !parts.getLast().contains(text)) {
        parts.add(text);
      }
      current = current.getCause();
    }
    return String.join(CAUSED_BY, parts);

  }

  private static String oneLine(
      final String text) {

    return LINE_BREAKS.matcher(text).replaceAll(" ");

  }

  /**
   * Cuts the text to {@link #MAX_BYTES} bytes of UTF-8, at a whole character, and marks the
   * cut.
   *
   * @param text The whole text
   * @return The text as it is where it fits, or its beginning plus {@link #CUT_MARK}
   */
  static String cut(
      final String text) {

    if (text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
      return text;
    }
    final var room = MAX_BYTES - CUT_MARK.length();
    final var kept = new StringBuilder();
    var bytes = 0;
    for (final int codePoint : text.codePoints().toArray()) {
      final var width = Character.toString(codePoint).getBytes(StandardCharsets.UTF_8).length;
      if (bytes + width > room) {
        break;
      }
      kept.appendCodePoint(codePoint);
      bytes += width;
    }
    return kept.append(CUT_MARK).toString();

  }

}
