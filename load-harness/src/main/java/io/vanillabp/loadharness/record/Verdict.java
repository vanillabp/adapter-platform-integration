package io.vanillabp.loadharness.record;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a week's comparison means.
 * <p>
 * A changed statement count is red at once. One more statement per start or per completion is
 * a change, and there is no threshold to argue about. A number which has to be zero and is
 * not is red as well. A time is red only when the ratio of the medians was above
 * {@link #THRESHOLD} in this week AND in the week before. One week above it is a note, not an
 * alarm, because the noise of a shared runner reaches that far on its own. The rules are
 * decision 150 in the repository's DECISIONS.md.
 *
 * @param state Green, a note, or red
 * @param reasons Why, one sentence per finding, empty when green
 */
public record Verdict(
                      State state,
                      List<String> reasons) {

  /**
   * The ratio of the medians above which a time counts as slower.
   */
  public static final double THRESHOLD = 1.10;

  /**
   * The three outcomes of a week.
   */
  public enum State {

    /**
     * Nothing to say.
     */
    GREEN,

    /**
     * A time missed the threshold in this week only. Kept in the record, no alarm.
     */
    NOTE,

    /**
     * A statement count moved, a number which has to be zero is not, or a time missed the
     * threshold in two weeks in a row.
     */
    RED;

    /**
     * How the state is written into the record.
     *
     * @return The state in lower case
     */
    public String text() {

      return name().toLowerCase(java.util.Locale.ROOT);

    }

  }

  /**
   * Judges a comparison.
   *
   * @param comparison This week's comparison
   * @param previousRow The newest row of the same platform in the record, if there is one
   * @return The verdict
   */
  public static Verdict judge(
      final Comparison comparison,
      final Optional<Map<String, String>> previousRow) {

    final var red = new ArrayList<String>();
    final var notes = new ArrayList<String>();
    comparison.figures().forEach((
        metric,
        figure) -> {
      switch (metric.kind()) {
        case STATEMENTS -> {
          if (figure.baseline() != figure.candidate()) {
            red
                .add("%s moved from %s to %s"
                    .formatted(
                        metric.key(),
                        LoadRecord.format(figure.baseline()),
                        LoadRecord.format(figure.candidate())));
          }
        }
        case ZERO -> {
          if (figure.largest() > 0) {
            red.add("%s is %s and has to be 0".formatted(metric.key(), LoadRecord.format(figure.largest())));
          }
        }
        case TIME -> {
          if (!(figure.ratio() > THRESHOLD)) {
            return;
          }
          final var before = previousRow
              .map(row -> row.get(metric.key()
                  + ".ratio"))
              .filter(text -> !text.isBlank())
              .map(Double::valueOf)
              .orElse(Double.NaN);
          if (before > THRESHOLD) {
            red
                .add("%s ratio %s and %s in two weeks in a row"
                    .formatted(metric.key(), LoadRecord.format(before), LoadRecord.format(figure.ratio())));
          } else {
            notes.add("%s ratio %s in this week only".formatted(metric.key(), LoadRecord.format(figure.ratio())));
          }
        }
        case INFO -> {
          // kept and never judged
        }
      }
    });
    if (!red.isEmpty()) {
      final var reasons = new ArrayList<>(red);
      reasons.addAll(notes);
      return new Verdict(State.RED, List.copyOf(reasons));
    }
    if (!notes.isEmpty()) {
      return new Verdict(State.NOTE, List.copyOf(notes));
    }
    return new Verdict(State.GREEN, List.of());

  }

}
