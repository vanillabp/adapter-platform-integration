package io.vanillabp.integration.adapter.migration.startup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where every startup check says what it found, instead of writing a line of its own.
 * <p>
 * VanillaBP looks at a lot while an application starts and used to say each of it on its
 * own, between the lines of every other library. What this collects is written once, at
 * the end of the start, as one block between two rulers: a reader sees in one glance
 * whether there is anything to look at, and what part of their application to open.
 * <p>
 * An application with nothing to notice gets nothing at all - no ruler, no heading, no
 * empty message. That is the point of the whole thing, so a healthy start reads as it did
 * before.
 *
 * <h2>The two ends</h2>
 *
 * What a start survives goes into the box. What ends a start is collected as well and
 * thrown once, by {@link #endOfStartup()}, in the same grouping the box uses - a developer
 * who put two things wrong learns both in one start rather than one per restart, and the
 * two texts read alike. What was already noticed when the refusal fell due is written
 * before it is thrown: a warning does not disappear because the start later fails.
 * <p>
 * The limit is a check which cannot go on once its question was answered badly. Such a
 * check throws where it stands, and its javadoc says so. Everything else reports here and
 * lets the start walk to its end.
 *
 * <h2>What never reaches this</h2>
 *
 * Quarkus ends a BUILD for three of its checks, and the Hazelcast election cache refuses a
 * native image. An application which was never built never starts, so those are not
 * missing here - they are in the log of the build.
 *
 * <h2>Counting and folding</h2>
 *
 * The same finding arrives once per workflow module, per BPMN process, per adapter id or
 * per method. Findings are therefore reported unformatted, with their scope beside their
 * text, and two findings carrying the same text under the same topic become ONE entry
 * naming both scopes. Which is why a check hands over the scope separately instead of
 * writing it into the sentence.
 * <p>
 * See decision &lt;pending: 579&gt; in the repository's DECISIONS.md.
 */
public class StartupFindings {

  private static final Logger log = LoggerFactory.getLogger(StartupFindings.class);

  /**
   * The line above and below the box. The one fixed width in it: the messages name the way
   * out and are long for that reason, so a frame around each of them would take the room
   * they need. A ruler costs one line and survives a narrow terminal, because nothing is
   * aligned to it.
   */
  static final String RULER = "-".repeat(102);

  /**
   * What the block calls the framework it comes from. The release line is written out
   * because a reader with two applications in one log has to see which of them this is,
   * and it stands here rather than inside two sentences so that the next line moves it
   * once.
   */
  static final String WHO_LOOKED = "VanillaBP 2.0";

  /** What the box says about itself when nothing ended the start. */
  static final String NOTHING_STOPPED_THE_START = "None of them stopped the start.";

  /** What the box says about itself when the start ends right after it. */
  static final String THE_START_ENDS_AFTER_THEM = "The start ends after them, see the refusal below.";

  /**
   * How loud one finding is.
   */
  public enum Severity {

    /** The start goes on and something is still asked of the developer. */
    NOTICE,

    /** The start goes on and something is wrong. */
    WARNING,

    /** The start goes on and something is wrong enough to be written at error level. */
    ERROR,

    /** The start does not go on. Collected, and thrown by {@link #endOfStartup()}. */
    REFUSAL

  }

  /**
   * One thing worth a look.
   *
   * @param severity How loud it is
   * @param topic Where its fix lies
   * @param scope What it is about - a workflow module, a BPMN process, an adapter id, a
   *          property key - written as the developer knows it and never folded into the
   *          text, so the same finding over twelve modules can become one entry
   * @param message The whole message, the way it is written to a developer, ending with
   *          what to do
   */
  public record Finding(
                        Severity severity,
                        StartupTopic topic,
                        String scope,
                        String message) {
  }

  /**
   * What was found, in the order it was found. Synchronized because the deployment of
   * several workflow modules may run in parallel on a platform which chooses to, and
   * because a check reporting from a callback of an adapter is not always on the thread
   * which started it.
   */
  private final List<Finding> findings = Collections.synchronizedList(new ArrayList<>());

  /**
   * Notes something the start survives and which still asks the developer for something.
   *
   * @param topic Where its fix lies
   * @param scope What it is about
   * @param message The whole message, ending with what to do
   */
  public void notice(
      final StartupTopic topic,
      final String scope,
      final String message) {

    add(Severity.NOTICE, topic, scope, message);

  }

  /**
   * Notes something which is wrong and which the start survives.
   *
   * @param topic Where its fix lies
   * @param scope What it is about
   * @param message The whole message, ending with what to do
   */
  public void warn(
      final StartupTopic topic,
      final String scope,
      final String message) {

    add(Severity.WARNING, topic, scope, message);

  }

  /**
   * Notes something which is wrong enough to be written at error level and which the start
   * still survives.
   *
   * @param topic Where its fix lies
   * @param scope What it is about
   * @param message The whole message, ending with what to do
   */
  public void error(
      final StartupTopic topic,
      final String scope,
      final String message) {

    add(Severity.ERROR, topic, scope, message);

  }

  /**
   * Notes a reason the start cannot go on with. It is thrown by {@link #endOfStartup()},
   * together with every other reason found until then, so a developer who put two things
   * wrong learns both at once.
   * <p>
   * A check which cannot let the start walk on - because the next check would ask a
   * question this one just proved unanswerable - throws where it stands instead and says
   * so in its javadoc.
   *
   * @param topic Where its fix lies
   * @param scope What it is about
   * @param message The whole message, ending with what to do
   */
  public void refuse(
      final StartupTopic topic,
      final String scope,
      final String message) {

    add(Severity.REFUSAL, topic, scope, message);

  }

  private void add(
      final Severity severity,
      final StartupTopic topic,
      final String scope,
      final String message) {

    if ((topic == null) || (message == null)) {
      return;
    }
    final var finding = new Finding(severity, topic, scope, message);
    synchronized (findings) {
      // the same check runs again on a second workflow module, and a platform may
      // validate one configuration object twice: the same sentence about the same scope
      // is one finding
      if (!findings.contains(finding)) {
        findings.add(finding);
      }
    }

  }

  /**
   * What was reported so far, in the order it was reported - what a test reads instead of
   * the rendered text, and the only way to ask this object anything.
   *
   * @return The findings, a copy
   */
  public List<Finding> findings() {

    synchronized (findings) {
      return List.copyOf(findings);
    }

  }

  /**
   * Whether this start has nothing to say at all, which is the case worth being fast
   * about: a healthy application writes no box and no ruler.
   *
   * @return Whether nothing was reported
   */
  public boolean nothingToSay() {

    return findings().isEmpty();

  }

  /**
   * Whether the box was written already. The end of a start happens once, and a platform
   * which starts the workflow processing of its modules in two calls must not get two
   * boxes for it.
   */
  private boolean written = false;

  /**
   * The end of the start: writes the box, then throws what was refused.
   * <p>
   * The box goes into the log in ONE call, so no other library's line can land in the
   * middle of it, and it is written BEFORE the refusal is thrown, because a warning found
   * on the way does not become less true when a later check ends the start.
   * <p>
   * Called a second time it does nothing: a start ends once.
   *
   * @throws IllegalStateException Carrying every reason the start cannot go on, grouped
   *           the way the box is grouped
   */
  public void endOfStartup() {

    synchronized (findings) {
      if (written) {
        return;
      }
      written = true;
    }
    final var box = theBox();
    if (box != null) {
      if (containsAnything(Severity.WARNING) || containsAnything(Severity.ERROR)) {
        log.warn(box);
      } else {
        log.info(box);
      }
    }
    final var refusal = theRefusal();
    if (refusal != null) {
      throw refusal;
    }

  }

  /**
   * The box, as it goes into the log: a ruler, one line saying how much there is, the
   * findings grouped by topic with their count, and a ruler.
   *
   * @return The rendered box, or <code>null</code> where nothing survived the start worth
   *         a look
   */
  public String theBox() {

    final var entries = entriesOf(
        finding -> finding.severity() != Severity.REFUSAL);
    if (entries.isEmpty()) {
      return null;
    }
    final var total = total(entries);
    return render(
        "%s looked at this application and found %d %s worth a look. %s"
            .formatted(
                WHO_LOOKED,
                total,
                total == 1
                    ? "thing"
                    : "things",
                containsAnything(Severity.REFUSAL)
                    ? THE_START_ENDS_AFTER_THEM
                    : NOTHING_STOPPED_THE_START),
        entries);

  }

  /**
   * Everything which ends this start, as one exception - grouped and counted the way the
   * box is, so a developer reads the two the same way.
   *
   * @return The exception to throw, or <code>null</code> where nothing was refused
   */
  public IllegalStateException theRefusal() {

    final var entries = entriesOf(
        finding -> finding.severity() == Severity.REFUSAL);
    if (entries.isEmpty()) {
      return null;
    }
    final var total = total(entries);
    return new IllegalStateException(
        render(
            "%s cannot start this application: %s to change."
                .formatted(
                    WHO_LOOKED,
                    total == 1
                        ? "one thing has"
                        : "%d things have".formatted(total)),
            entries));

  }

  private boolean containsAnything(
      final Severity severity) {

    return findings()
        .stream()
        .anyMatch(finding -> finding.severity() == severity);

  }

  /**
   * How many entries the box shows - what a reader counts, so it is the number AFTER two
   * findings of one text were folded into one entry.
   */
  private static int total(
      final Map<StartupTopic, List<Entry>> entries) {

    return entries
        .values()
        .stream()
        .mapToInt(List::size)
        .sum();

  }

  /**
   * One line of the box: a scope line and the message below it. Two findings of one topic
   * carrying the same message are one entry naming both scopes.
   *
   * @param message The message
   * @param scopes What it is about, in the order the findings arrived
   */
  private record Entry(
                       String message,
                       List<String> scopes) {
  }

  /**
   * Folds the findings into the entries of each topic, in the order the topics are
   * declared in and, inside a topic, loudest first.
   */
  private Map<StartupTopic, List<Entry>> entriesOf(
      final java.util.function.Predicate<Finding> wanted) {

    final var byTopic = new LinkedHashMap<StartupTopic, List<Entry>>();
    for (final var topic : StartupTopic.values()) {
      final var ofTopic = findings()
          .stream()
          .filter(wanted)
          .filter(finding -> finding.topic() == topic)
          // a finding which costs more is read first: what stops a workflow before what
          // is merely never read. The severities are declared quietest first, so the
          // order of this list is the reverse of theirs
          .sorted(java.util.Comparator.comparingInt((
              Finding finding) -> finding.severity().ordinal()).reversed())
          .toList();
      if (ofTopic.isEmpty()) {
        continue;
      }
      final var folded = new LinkedHashMap<String, List<String>>();
      for (final var finding : ofTopic) {
        folded
            .computeIfAbsent(finding.message(), message -> new ArrayList<>())
            .add(finding.scope());
      }
      byTopic
          .put(
              topic,
              folded
                  .entrySet()
                  .stream()
                  .map(entry -> new Entry(entry.getKey(), entry.getValue()))
                  .toList());
    }
    return byTopic;

  }

  /**
   * Draws what {@link #entriesOf(java.util.function.Predicate)} folded, below the given
   * head line.
   */
  private static String render(
      final String headLine,
      final Map<StartupTopic, List<Entry>> entries) {

    final var text = new StringBuilder(RULER)
        .append('\n')
        .append(headLine)
        .append('\n');
    entries
        .forEach((
            topic,
            ofTopic) -> {
          text
              .append('\n')
              .append(topic.heading())
              .append(" (")
              .append(ofTopic.size())
              .append(")\n");
          ofTopic.forEach(entry -> {
            final var scope = entry
                .scopes()
                .stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(", "));
            if (!scope.isEmpty()) {
              text
                  .append("  ")
                  .append(scope)
                  .append('\n');
            }
            text
                .append(indented(entry.message()))
                .append('\n');
          });
        });
    return text
        .append(RULER)
        .toString();

  }

  /**
   * Puts a message under its scope line. Every line of it is moved, the ones a message
   * breaks itself included, because a message which sets out a list loses that list
   * otherwise.
   */
  private static String indented(
      final String message) {

    return message
        .strip()
        .lines()
        .map(line -> "      "
            + line)
        .collect(Collectors.joining("\n"));

  }

}
