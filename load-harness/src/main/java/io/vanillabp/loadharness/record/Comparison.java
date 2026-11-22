package io.vanillabp.loadharness.record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The baseline and the candidate side by side: the median of every metric for both, the ratio
 * between the two medians, and the smallest and largest round of the candidate.
 * <p>
 * Medians, because the rounds of one side spread by about half on a loaded runner, while
 * their median moves by a few percent. The ratio, because both sides ran in the same job on
 * the same machine, so whatever the machine did to one it did to the other.
 *
 * @param figures One figure per metric which both sides measured
 */
public record Comparison(
                         Map<Metric, Figure> figures) {

  /**
   * One metric compared.
   *
   * @param baseline The median of the baseline
   * @param candidate The median of the candidate
   * @param ratio The candidate's median divided by the baseline's. One where both are zero,
   *          and <code>NaN</code> where only the baseline is zero
   * @param smallest The smallest round of the candidate
   * @param largest The largest round of the candidate
   */
  public record Figure(
                       double baseline,
                       double candidate,
                       double ratio,
                       double smallest,
                       double largest) {
  }

  /**
   * Compares the rounds of both sides.
   *
   * @param baseline The rounds of the baseline
   * @param candidate The rounds of the candidate
   * @return The comparison
   */
  public static Comparison of(
      final List<RoundResult> baseline,
      final List<RoundResult> candidate) {

    if (baseline.isEmpty() || candidate.isEmpty()) {
      throw new IllegalArgumentException("A comparison needs at least one round of each side, but got %d of the "
          + "baseline and %d of the candidate".formatted(baseline.size(), candidate.size()));
    }
    final var figures = new EnumMap<Metric, Figure>(Metric.class);
    for (final var metric : Metric.values()) {
      final var baselineValues = valuesOf(baseline, metric);
      final var candidateValues = valuesOf(candidate, metric);
      if (baselineValues.isEmpty() || candidateValues.isEmpty()) {
        continue;
      }
      final var baselineMedian = median(baselineValues);
      final var candidateMedian = median(candidateValues);
      figures
          .put(metric, new Figure(
              baselineMedian, candidateMedian, ratio(baselineMedian, candidateMedian), Collections
                  .min(candidateValues), Collections.max(candidateValues)));
    }
    return new Comparison(figures);

  }

  private static List<Double> valuesOf(
      final List<RoundResult> rounds,
      final Metric metric) {

    final var values = new ArrayList<Double>();
    rounds
        .stream()
        .map(round -> round.value(metric))
        .filter(java.util.Objects::nonNull)
        .forEach(values::add);
    return values;

  }

  static double ratio(
      final double baseline,
      final double candidate) {

    if (baseline == 0) {
      return candidate == 0
          ? 1
          : Double.NaN;
    }
    return candidate / baseline;

  }

  static double median(
      final List<Double> values) {

    final var sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    final var middle = sorted.size() / 2;
    return sorted.size() % 2 == 1
        ? sorted.get(middle)
        : (sorted.get(middle - 1) + sorted.get(middle)) / 2;

  }

}
