package io.vanillabp.integration.adapter.migration.startup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;

/**
 * Where every startup check says what it found, instead of writing a line of its own.
 * <p>
 * VanillaBP looks at a lot while an application starts. It used to say each thing on its
 * own, between the lines of every other library. Now this class collects it all and writes
 * it once, at the end of the start, as one block between two rulers. A reader sees at one
 * glance whether there is anything to look at, and which part of the application to open.
 * <p>
 * An application with nothing to notice gets nothing at all: no ruler, no heading, no
 * empty message. That is the point of the whole thing. A healthy start reads as it did
 * before.
 *
 * <h2>The two ends</h2>
 *
 * What a start survives goes into the box. What ends a start is collected as well. It is
 * thrown once, by {@link #endOfStartup()}, grouped the same way as the box. A developer
 * who got two things wrong learns both in one start, not one per restart, and the two
 * texts read alike. What was noticed before the refusal is written before it is thrown. A
 * warning does not disappear because the start fails later.
 * <p>
 * There is one limit. Some checks cannot go on once their question got a bad answer. Such
 * a check throws where it stands, and its javadoc says so. Everything else reports here
 * and lets the start run to its end.
 *
 * <h2>What never reaches this</h2>
 *
 * Quarkus ends a BUILD for three of its checks, and the Hazelcast election cache refuses a
 * native image. An application which was never built never starts. So those findings are
 * not missing here. They are in the log of the build.
 *
 * <h2>When the start ends on something else</h2>
 *
 * An exception which has nothing to do with this object can end a start too. Everything
 * found until then would be lost with it. {@link #sayWhatWasFoundBeforeTheStartFailed()}
 * is the other end for that case. It writes the box and throws nothing. So what was known
 * stands next to the exception which the developer is about to read.
 *
 * <h2>A finding which arrives too late</h2>
 *
 * Once the box is written, a finding cannot go into it any more. So it is logged where it
 * was found, the way every finding was logged before the box existed. Two checks report
 * that late on purpose. One is what a dispatch learns about an adapter id which nobody
 * configures any more. The other is what an adapter notices while it runs.
 *
 * <h2>Counting and folding</h2>
 *
 * The same finding arrives once per workflow module, BPMN process, adapter id or method.
 * So findings are reported unformatted, with their scope next to their text. Two findings
 * with the same text under the same topic become ONE entry which names both scopes. That
 * is why a check hands over the scope separately and does not write it into the sentence.
 *
 * <h2>Who reports here</h2>
 *
 * The checks of the platform hold this class. An adapter holds {@link StartupReport}. That
 * is only the reporting half of this class. When a start is over, and what happens to a
 * reason not to start, are decisions of the core.
 * <p>
 * See decision 96 and decision 97 in the repository's DECISIONS.md.
 */
public class StartupFindings implements StartupReport {

  private static final Logger log = LoggerFactory.getLogger(StartupFindings.class);

  /**
   * Builds an empty collection of findings. One object belongs to one start of one
   * application. It gathers what the checks noticed and writes it once at the end. So the
   * constructor takes nothing, and the checks hand over their findings later.
   */
  public StartupFindings() {
  }

  /**
   * The line above and below the box. It is the only fixed width in the box. The messages
   * name the way out and are long for that reason, so a frame around each of them would
   * take the room they need. A ruler costs one line. It still works in a narrow terminal,
   * because nothing is aligned to it.
   */
  static final String RULER = "-".repeat(102);

  /**
   * What the block calls the framework it comes from. It names the release line, because a
   * reader may have two applications in one log and has to see which one this is. It is a
   * constant and not part of two sentences, so the next release line changes it once.
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
   * @param scope What it is about: a workflow module, a BPMN process, an adapter id or a
   *          property key. It is written as the developer knows it, and never folded into
   *          the text. So the same finding over twelve modules can become one entry
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
   * What was found, in the order it was found. The list is synchronized for two reasons. A
   * platform may deploy several workflow modules in parallel. And a check which reports
   * from a callback of an adapter is not always on the thread which started it.
   */
  private final List<Finding> findings = Collections.synchronizedList(new ArrayList<>());

  /**
   * Notes something the start survives and which still asks the developer for something.
   *
   * @param topic Where its fix lies
   * @param scope What it is about
   * @param message The whole message, ending with what to do
   */
  @Override
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
  @Override
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
  @Override
  public void error(
      final StartupTopic topic,
      final String scope,
      final String message) {

    add(Severity.ERROR, topic, scope, message);

  }

  /**
   * Notes a reason why the start cannot go on. {@link #endOfStartup()} throws it, together
   * with every other reason found until then. So a developer who got two things wrong
   * learns both at once.
   * <p>
   * Some checks cannot let the start go on, because the next check would ask a question
   * which this one just proved cannot be answered. Such a check throws where it stands
   * instead, and its javadoc says so.
   * <p>
   * A refusal reported after the box was written is only logged, at error level. The start
   * it was meant to stop is over. Throwing then would end a running application because of
   * a finding about its start.
   *
   * @param topic Where its fix lies
   * @param scope What it is about
   * @param message The whole message, ending with what to do
   */
  @Override
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
      if (!written) {
        // the same check runs again for a second workflow module, and a platform may
        // validate one configuration object twice. The same sentence about the same scope
        // is one finding
        if (!findings.contains(finding)) {
          findings.add(finding);
        }
        return;
      }
    }
    sayItWhereItWasFound(finding);

  }

  /**
   * Writes a finding which arrives after the box was written.
   * <p>
   * The box is gone by then. So the finding goes into the log on its own, the way every
   * finding did before the box existed. Two checks report that late on purpose. A dispatch
   * which meets the leftovers of an adapter id which nobody configures any more writes that
   * message again. And an adapter may notice something while it runs. Dropping such a
   * finding would be the worst of the three answers.
   */
  private void sayItWhereItWasFound(
      final Finding finding) {

    final var text = finding.scope() == null
        ? finding.message()
        : "%s: %s".formatted(finding.scope(), finding.message());
    switch (finding.severity()) {
      case NOTICE -> log.info(text);
      case WARNING -> log.warn(text);
      case ERROR, REFUSAL -> log.error(text);
    }

  }

  /**
   * What was reported so far, in the order it was reported. A test reads this instead of
   * the rendered text. It is the only way to ask this object anything.
   *
   * @return The findings, a copy
   */
  public List<Finding> findings() {

    synchronized (findings) {
      return List.copyOf(findings);
    }

  }

  /**
   * Whether this start has nothing to say at all. That case should be fast: a healthy
   * application writes no box and no ruler.
   *
   * @return Whether nothing was reported
   */
  public boolean nothingToSay() {

    return findings().isEmpty();

  }

  /**
   * Whether a reason not to start was found already.
   * <p>
   * The core asks this before it tells the adapters to begin. A refusal is collected and
   * thrown at the end of the start. An adapter which started its workers in between would
   * hand out tasks to an application which is about to end.
   *
   * @return Whether the start has to be refused
   */
  public boolean somethingWasRefused() {

    return containsAnything(Severity.REFUSAL);

  }

  /**
   * Whether the box was written already. A start ends once. A platform which starts the
   * workflow processing of its modules in two calls must not get two boxes.
   */
  private boolean written = false;

  /**
   * The end of the start. It writes the box and then throws what was refused.
   * <p>
   * The box goes into the log in ONE call, so no line of another library can land in the
   * middle of it. It is written BEFORE the refusal is thrown. A warning found on the way is
   * still true when a later check ends the start.
   * <p>
   * A second call does nothing, because a start ends once.
   * <p>
   * The platform integration says when that moment has come, through
   * {@code DeploymentService#endOfStartup}. The two platforms reach it at different places.
   * Quarkus deploys and starts the workflow processing in one observer of its startup event.
   * It ends the start after both. Spring Boot ends the start with the deployment, inside the
   * lifecycle. The start of the workflow processing waits until the application is ready,
   * and a context which is only refreshed never gets there.
   * <p>
   * So the same application writes a different box on the two platforms, and that is
   * intended. On Quarkus, everything which the start of the workflow processing notices is
   * inside the box. On Spring Boot it is a late finding, written into the log where it was
   * found. Moving the end of a Spring Boot start behind the ready event would cost every
   * refreshed context its box. A second, smaller box after the event would leave a reader
   * with two boxes and the question which finding belongs in which.
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
   * The other end of a start. Something else ended it, so this writes the box and throws
   * nothing.
   * <p>
   * A start can end on an exception which has nothing to do with this object: a database
   * which is not there, a bean which cannot be built, a check which has to throw where it
   * stands. Everything found until then would be lost with it. But that is the moment a
   * developer needs it most. The warning about a workflow module which nobody configured is
   * often the reason for the exception one line below.
   * <p>
   * So this writes what was found, refusals included. Here they are part of the box and not
   * an exception of their own. The developer is about to read the exception which ended
   * this start, and a second one would only compete with it.
   * <p>
   * After {@link #endOfStartup()} it does nothing, because the box was written already.
   */
  public void sayWhatWasFoundBeforeTheStartFailed() {

    synchronized (findings) {
      if (written) {
        return;
      }
      written = true;
    }
    final var entries = entriesOf(finding -> true);
    if (entries.isEmpty()) {
      return;
    }
    final var total = total(entries);
    log
        .warn(
            render(
                "%s looked at this application and found %d %s worth a look. The start ended on something else, so this is what was known by then."
                    .formatted(
                        WHO_LOOKED,
                        total,
                        total == 1
                            ? "thing"
                            : "things"),
                entries));

  }

  /**
   * The box as it goes into the log: a ruler, one line which says how much there is, the
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
   * Everything which ends this start, as one exception. It is grouped and counted like the
   * box, so a developer reads both the same way.
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
   * How many entries the box shows. This is what a reader counts, so it is the number AFTER
   * two findings with the same text were folded into one entry.
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
   * One entry of the box: a scope line and the message below it. Two findings of one topic
   * with the same message are one entry which names both scopes.
   *
   * @param message The message
   * @param scopes What it is about, in the order the findings arrived
   */
  private record Entry(
                       String message,
                       List<String> scopes) {
  }

  /**
   * Folds the findings into the entries of each topic. The topics keep the order in which
   * they are declared. Inside a topic, the loudest finding comes first.
   */
  private Map<StartupTopic, List<Entry>> entriesOf(
      final java.util.function.Predicate<Finding> wanted) {

    final var byTopic = new LinkedHashMap<StartupTopic, List<Entry>>();
    for (final var topic : StartupTopic.values()) {
      final var ofTopic = findings()
          .stream()
          .filter(wanted)
          .filter(finding -> finding.topic() == topic)
          // a finding which costs more is read first: what stops a workflow comes before
          // what is never read. The severities are declared quietest first, so this order is
          // the reverse of theirs
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
   * Puts a message under its scope line. Every line of the message is indented, also the
   * lines where the message breaks itself. Otherwise a message which sets out a list would
   * lose that list.
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
