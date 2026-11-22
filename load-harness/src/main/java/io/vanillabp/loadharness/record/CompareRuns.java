package io.vanillabp.loadharness.record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Compares the rounds of the baseline with those of the candidate, appends one row to the
 * record and says what it means. The weekly workflow calls it once per platform:
 *
 * <pre>
 * java -cp load-harness.jar io.vanillabp.loadharness.record.CompareRuns \
 *   --record load/spring-boot.csv --platform spring-boot \
 *   --baseline-rounds rounds/spring-boot/baseline --candidate-rounds rounds/spring-boot/candidate \
 *   --date 2026-11-22 --candidate-commit 0123abc --baseline-ref 4567def \
 *   --runner "AMD EPYC 7763" --cores 4 --workflows 1000 --service-tasks 3 \
 *   --verdict verdict-spring-boot.md
 * </pre>
 * <p>
 * The row is written even when the week is red, and marked as such. A missing row would hide
 * the week which mattered (see {@code DECISIONS.pending/384.md}). The exit code says what
 * happened:
 * <ul>
 * <li>0: the row is written, and the week is green or has a note,</li>
 * <li>1: nothing was written, because the record or a round could not be read. A run which
 * cannot read the history must not publish a shortened one,</li>
 * <li>2: the row is written, and the week is red.</li>
 * </ul>
 */
public final class CompareRuns {

  /**
   * The exit code of a red week whose row was written.
   */
  public static final int RED = 2;

  /**
   * The exit code of a run which wrote nothing.
   */
  public static final int NOTHING_WRITTEN = 1;

  private CompareRuns() {
  }

  /**
   * Runs the comparison from the command line.
   *
   * @param args The options, see the class comment
   */
  public static void main(
      final String[] args) {

    System.exit(run(args));

  }

  /**
   * Runs the comparison and returns the exit code instead of ending the JVM.
   *
   * @param args The options, see the class comment
   * @return The exit code, see the class comment
   */
  public static int run(
      final String[] args) {

    final Map<String, String> options;
    try {
      options = options(args);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return NOTHING_WRITTEN;
    }
    try {
      final var record = LoadRecord.read(Path.of(options.get("record")));
      final var comparison = Comparison
          .of(rounds(Path.of(options.get("baseline-rounds"))), rounds(Path.of(options.get("candidate-rounds"))));
      final var platform = options.get("platform");
      final var verdict = Verdict.judge(comparison, record.lastRowOf(platform));
      record.append(row(options, comparison, verdict));
      record.write(Path.of(options.get("record")));
      final var summary = summary(platform, comparison, verdict);
      System.out.println(summary);
      if (options.containsKey("verdict")) {
        Files.writeString(Path.of(options.get("verdict")), summary, StandardCharsets.UTF_8);
      }
      return verdict.state() == Verdict.State.RED
          ? RED
          : 0;
    } catch (IOException | RuntimeException e) {
      System.err.println("Nothing was written to the record: "
          + e.getMessage());
      return NOTHING_WRITTEN;
    }

  }

  private static final List<String> REQUIRED = List
      .of("record", "platform", "baseline-rounds", "candidate-rounds", "date", "candidate-commit", "baseline-ref",
          "runner", "cores", "workflows", "service-tasks");

  private static Map<String, String> options(
      final String[] args) {

    final var options = new HashMap<String, String>();
    for (var index = 0; index < args.length; index++) {
      if (!args[index].startsWith("--") || (index + 1 >= args.length)) {
        throw new IllegalArgumentException("Expected '--name value' pairs, but got '%s'".formatted(args[index]));
      }
      options.put(args[index].substring(2), args[++index]);
    }
    final var missing = REQUIRED.stream().filter(name -> !options.containsKey(name)).toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("Missing option(s): --%s".formatted(String.join(", --", missing)));
    }
    return options;

  }

  private static List<RoundResult> rounds(
      final Path directory) throws IOException {

    final var rounds = new ArrayList<RoundResult>();
    try (Stream<Path> files = Files.list(directory)) {
      for (final var file : files.filter(path -> path.toString().endsWith(".properties")).sorted().toList()) {
        rounds.add(RoundResult.read(file));
      }
    }
    if (rounds.isEmpty()) {
      throw new IOException("%s holds no round".formatted(directory));
    }
    return rounds;

  }

  private static Map<String, String> row(
      final Map<String, String> options,
      final Comparison comparison,
      final Verdict verdict) {

    final var row = new LinkedHashMap<String, String>();
    row.put("date", field(options.get("date")));
    row.put("candidate", field(options.get("candidate-commit")));
    row.put("baseline", field(options.get("baseline-ref")));
    row.put("platform", field(options.get("platform")));
    row.put("runner", field(options.get("runner")));
    row.put("cores", field(options.get("cores")));
    row.put("workflows", field(options.get("workflows")));
    row.put("service.tasks", field(options.get("service-tasks")));
    row.put("verdict", verdict.state().text());
    row.put("notes", field(String.join("; ", verdict.reasons())));
    comparison.figures().forEach((
        metric,
        figure) -> {
      row.put(metric.key()
          + ".baseline", LoadRecord.format(figure.baseline()));
      row.put(metric.key()
          + ".candidate", LoadRecord.format(figure.candidate()));
      row.put(metric.key()
          + ".ratio", LoadRecord.format(figure.ratio()));
      row.put(metric.key()
          + ".min", LoadRecord.format(figure.smallest()));
      row.put(metric.key()
          + ".max", LoadRecord.format(figure.largest()));
    });
    return row;

  }

  /**
   * A field of the record holds no comma and no line break, because the record is a plain
   * CSV file without quoting.
   */
  private static String field(
      final String text) {

    return text.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();

  }

  private static String summary(
      final String platform,
      final Comparison comparison,
      final Verdict verdict) {

    final var text = new StringBuilder();
    text.append("Load run on %s: %s%n%n".formatted(platform, verdict.state().text()));
    verdict.reasons().forEach(reason -> text.append("- ").append(reason).append(System.lineSeparator()));
    if (!verdict.reasons().isEmpty()) {
      text.append(System.lineSeparator());
    }
    text.append("| metric | baseline | candidate | ratio |").append(System.lineSeparator());
    text.append("|---|---|---|---|").append(System.lineSeparator());
    comparison
        .figures()
        .forEach((
            metric,
            figure) -> text
                .append("| %s | %s | %s | %s |%n"
                    .formatted(
                        metric.key(),
                        LoadRecord.format(figure.baseline()),
                        LoadRecord.format(figure.candidate()),
                        LoadRecord.format(figure.ratio()))));
    return text.toString();

  }

}
