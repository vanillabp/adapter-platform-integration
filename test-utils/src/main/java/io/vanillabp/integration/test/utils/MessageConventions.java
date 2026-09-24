package io.vanillabp.integration.test.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The convention a build can check about a guiding message: it reads as a sentence.
 * <p>
 * A message is the manual a developer of a VanillaBP application reads in the moment
 * something is wrong, so a sentence which fell apart in the source costs them the one
 * explanation they get. One did: a text block was pulled onto a single line while its
 * indentation stayed where it was, and the reader got thirteen spaces in the middle of a
 * sentence. It stood like that over two changes, because nothing reads a message and
 * nobody diffs a string they did not touch.
 * <p>
 * Two things are checked. A run of spaces between two words is read from the VALUE of a
 * string, because a text block loses its indentation on the way to the reader and the check
 * strips it the way Java does. Two words glued into one are read from the source instead,
 * because the joining is what hides them. Only the main sources are read: a message a test
 * writes reaches nobody.
 * <p>
 * Two more rules were measured and left out. A message without a closing full stop cannot be
 * told from a fragment, since most messages are assembled from several strings and the end
 * of one of them is not the end of a sentence. And whether a message names a way out is not
 * a question a string can answer; a message which only reports what happened is sometimes
 * the right one.
 */
public final class MessageConventions {

  /**
   * Two words with more than one space between them, which is what a sentence pulled
   * apart looks like once the indentation is gone.
   * <p>
   * Both sides have to be a word for a reason. A run of spaces which lines up the
   * comments of a configuration sample is there on purpose, and so is the second space
   * somebody puts after a full stop. Neither of those has a word to its left AND a word to
   * its right, and both stand in this repository.
   */
  private static final Pattern WORDS_PULLED_APART = Pattern.compile("\\w {2,}\\w");

  /**
   * A string in the source: a plain literal, with its escapes as they are written.
   */
  private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

  /**
   * A line of a text block which is continued into the next one and ends with a word.
   * <p>
   * The continuation joins the two lines without anything between them, so a word at the
   * end of the first one and a word at the start of the second one arrive as one word.
   * That defect cannot be seen in the value any more, which is why this reads the source.
   * A line ending with something else is left alone: a CREATE TABLE broken after its
   * opening bracket is written that way on purpose, and this repository holds such lines.
   */
  private static final Pattern CONTINUED_AFTER_A_WORD = Pattern.compile("\\w\\\\$");

  /**
   * What a check found: one string whose sentence does not read as one.
   *
   * @param file The source file, relative to the repository root
   * @param line The line the string starts at, so a failing build can be opened there
   * @param text The value of that string, as the developer reads it
   */
  public record BrokenMessage(Path file, int line, String text) {
  }

  private MessageConventions() {
  }

  /**
   * The messages of a repository which carry a run of spaces between two words.
   *
   * @param repositoryRoot The repository's root directory
   * @return What was found, in a stable order
   */
  public static List<BrokenMessage> messagesPulledApart(
      final Path repositoryRoot) {

    try (var files = Files.walk(repositoryRoot)) {
      return files
          .filter(MessageConventions::isMainSourceFile)
          .sorted()
          .flatMap(file -> messagesPulledApartIn(repositoryRoot.relativize(file), read(file)).stream())
          .toList();
    } catch (final IOException cannotRead) {
      throw new UncheckedIOException(
          "Could not read the main sources below '%s'".formatted(repositoryRoot), cannotRead);
    }

  }

  /**
   * The message of a failing check: which strings, and what to do about them.
   *
   * @param offenders What {@link #messagesPulledApart(Path)} returned
   * @return A message naming every offending string
   */
  public static String describeMessagesPulledApart(
      final Collection<BrokenMessage> offenders) {

    return """
        %d message(s) carry a run of spaces between two words, so a developer reads a sentence \
        which fell apart:
        %s
        A text block loses its indentation, and a line of it which was pulled onto the line \
        before keeps the spaces it had. Put the sentence back together and end each line of \
        the text block with a backslash and ONE space."""
        .formatted(
            offenders.size(),
            offenders
                .stream()
                .map(offender -> "  %s:%d: %s".formatted(offender.file(), offender.line(), offender.text()))
                .collect(Collectors.joining("\n")));

  }

  /**
   * The messages of a repository whose continuation glues two words into one.
   *
   * @param repositoryRoot The repository's root directory
   * @return What was found, in a stable order
   */
  public static List<BrokenMessage> messagesGluedTogether(
      final Path repositoryRoot) {

    try (var files = Files.walk(repositoryRoot)) {
      return files
          .filter(MessageConventions::isMainSourceFile)
          .sorted()
          .flatMap(file -> messagesGluedTogetherIn(repositoryRoot.relativize(file), read(file)).stream())
          .toList();
    } catch (final IOException cannotRead) {
      throw new UncheckedIOException(
          "Could not read the main sources below '%s'".formatted(repositoryRoot), cannotRead);
    }

  }

  /**
   * The message of a failing check: which strings, and what to do about them.
   *
   * @param offenders What {@link #messagesGluedTogether(Path)} returned
   * @return A message naming every offending string
   */
  public static String describeMessagesGluedTogether(
      final Collection<BrokenMessage> offenders) {

    return """
        %d line(s) of a text block end with a word and a backslash, so the word at the start of \
        the next line arrives stuck to it:
        %s
        A backslash joins the two lines with nothing between them. Put ONE space before it."""
        .formatted(
            offenders.size(),
            offenders
                .stream()
                .map(offender -> "  %s:%d: %s".formatted(offender.file(), offender.line(), offender.text()))
                .collect(Collectors.joining("\n")));

  }

  /**
   * @param file The source file, for the finding
   * @param source What it holds
   * @return What was found in it
   */
  static List<BrokenMessage> messagesGluedTogetherIn(
      final Path file,
      final String source) {

    final var found = new ArrayList<BrokenMessage>();
    final var lines = source.split("\n", -1);
    for (var lineNumber = 0; lineNumber < (lines.length - 1); lineNumber++) {
      final var line = lines[lineNumber].stripTrailing();
      final var next = lines[lineNumber + 1].strip();
      if (!CONTINUED_AFTER_A_WORD.matcher(line).find() || next.isEmpty()) {
        continue;
      }
      if (Character.isLetterOrDigit(next.charAt(0))) {
        found.add(new BrokenMessage(file, lineNumber + 1, line.strip()));
      }
    }
    return found;

  }

  /**
   * @param file The source file, for the finding
   * @param source What it holds
   * @return What was found in it
   */
  static List<BrokenMessage> messagesPulledApartIn(
      final Path file,
      final String source) {

    final var found = new ArrayList<BrokenMessage>();
    final var lines = source.split("\n", -1);
    for (var lineNumber = 0; lineNumber < lines.length; lineNumber++) {
      final var line = lines[lineNumber];
      if (opensATextBlock(line)) {
        final var closing = closingLineOf(lines, lineNumber);
        report(found, file, lineNumber + 1, textBlockValue(lines, lineNumber, closing));
        lineNumber = closing;
        continue;
      }
      final var literals = STRING_LITERAL.matcher(line);
      while (literals.find()) {
        report(found, file, lineNumber + 1, unescaped(literals.group(1)));
      }
    }
    return found;

  }

  /**
   * @param found Where a finding is collected
   * @param file The source file
   * @param line The line the string starts at
   * @param value What the developer reads
   */
  private static void report(
      final List<BrokenMessage> found,
      final Path file,
      final int line,
      final String value) {

    for (final var text : value.split("\n", -1)) {
      if (WORDS_PULLED_APART.matcher(text).find()) {
        found.add(new BrokenMessage(file, line, text.strip()));
        return;
      }
    }

  }

  /**
   * Whether a line opens a text block. A line holding the opening AND the closing
   * delimiter is a one-line text block, whose value has no indentation to lose, so it is
   * read as a plain string.
   *
   * @param line The line
   * @return Whether the text block goes on below this line
   */
  private static boolean opensATextBlock(
      final String line) {

    return countOf(line, "\"\"\"") == 1;

  }

  /**
   * @param lines The source
   * @param opening The line holding the opening delimiter
   * @return The line holding the closing delimiter, or the last line where the source
   *         holds none
   */
  private static int closingLineOf(
      final String[] lines,
      final int opening) {

    for (var line = opening + 1; line < lines.length; line++) {
      if (lines[line].contains("\"\"\"")) {
        return line;
      }
    }
    return lines.length - 1;

  }

  /**
   * What a text block hands the reader: its indentation stripped the way Java strips it,
   * and its line continuations joined.
   *
   * @param lines The source
   * @param opening The line holding the opening delimiter
   * @param closing The line holding the closing delimiter
   * @return The value, its lines separated by a newline
   */
  private static String textBlockValue(
      final String[] lines,
      final int opening,
      final int closing) {

    final var body = new ArrayList<String>();
    for (var line = opening + 1; line < closing; line++) {
      body.add(lines[line]);
    }
    // the closing delimiter may sit behind the last line of the text, and that line is
    // then part of the text like any other
    final var beforeTheClosingDelimiter = lines[closing].substring(0, lines[closing].indexOf("\"\"\""));
    if (!beforeTheClosingDelimiter.isBlank()) {
      body.add(beforeTheClosingDelimiter);
    }
    final var indentation = incidentalIndentationOf(
        body,
        beforeTheClosingDelimiter.isBlank() ? lines[closing] : null);
    final var value = new StringBuilder();
    var continued = false;
    for (final var line : body) {
      final var stripped = stripped(line, indentation);
      if (!continued && !value.isEmpty()) {
        value.append('\n');
      }
      continued = stripped.endsWith("\\");
      value.append(continued ? stripped.substring(0, stripped.length() - 1) : stripped);
    }
    return unescaped(value.toString());

  }

  /**
   * How much of the indentation of a text block belongs to the source rather than to the
   * value: the smallest indentation of a line which holds something, and of the line
   * carrying the closing delimiter.
   *
   * @param body The lines of the text, the one carrying the closing delimiter behind it
   *          included
   * @param closing The line holding the closing delimiter alone, <code>null</code> where
   *          text stands in front of that delimiter
   * @return The number of spaces Java takes off every line
   */
  private static int incidentalIndentationOf(
      final List<String> body,
      final String closing) {

    var indentation = closing == null ? Integer.MAX_VALUE : indentationOf(closing);
    for (final var line : body) {
      if (line.isBlank()) {
        continue;
      }
      indentation = Math.min(indentation, indentationOf(line));
    }
    return indentation;

  }

  private static int indentationOf(
      final String line) {

    var spaces = 0;
    while ((spaces < line.length()) && (line.charAt(spaces) == ' ')) {
      spaces++;
    }
    return spaces;

  }

  /**
   * @param line One line of a text block
   * @param indentation What Java takes off it
   * @return The line as the reader gets it, without the trailing spaces Java drops
   */
  private static String stripped(
      final String line,
      final int indentation) {

    final var withoutIndentation = line.length() > indentation
        ? line.substring(indentation)
        : line.strip();
    return withoutIndentation.stripTrailing();

  }

  /**
   * The escapes this check has to resolve. A newline written as an escape starts a line
   * of its own, and the two spaces behind it are then an indentation rather than a gap in
   * a sentence. Everything else stays as it is: no other escape turns into a space.
   *
   * @param text What the source holds
   * @return What the reader gets
   */
  private static String unescaped(
      final String text) {

    return text
        .replace("\\n", "\n")
        .replace("\\\"", "\"");

  }

  private static int countOf(
      final String line,
      final String what) {

    var occurrences = 0;
    var found = line.indexOf(what);
    while (found >= 0) {
      occurrences++;
      found = line.indexOf(what, found + what.length());
    }
    return occurrences;

  }

  private static boolean isMainSourceFile(
      final Path file) {

    final var path = file.toString().replace('\\', '/');
    if (!path.endsWith(".java") || path.contains("/target/")) {
      // a generated copy below 'target' is not a source, and the check must not judge it
      return false;
    }
    // 'src/main/java' also matches the per-release-line sources of an adapter
    return path.contains("/src/main/java");

  }

  private static String read(
      final Path file) {

    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (final IOException cannotRead) {
      throw new UncheckedIOException("Could not read '%s'".formatted(file), cannotRead);
    }

  }

}
