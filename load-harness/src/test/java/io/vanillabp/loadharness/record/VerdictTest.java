package io.vanillabp.loadharness.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The rules which turn a week red.
 */
@ExtendWith(SuppressOutputExtension.class)
public class VerdictTest {

  private static RoundResult round(
      final double statementsPerStart,
      final double wallMillis,
      final double discarded) {

    final var values = new EnumMap<Metric, Double>(Metric.class);
    values.put(Metric.STATEMENTS_START, statementsPerStart);
    values.put(Metric.WALL_TIME, wallMillis);
    values.put(Metric.OUTBOX_DISCARDED, discarded);
    return new RoundResult(values);

  }

  private static Comparison comparison(
      final RoundResult baseline,
      final RoundResult candidate) {

    return Comparison.of(List.of(baseline, baseline, baseline), List.of(candidate, candidate, candidate));

  }

  @Test
  @DisplayName("Equal counts and equal times are green")
  public void equalIsGreen() {

    final var verdict = Verdict.judge(comparison(round(5, 1000, 0), round(5, 1000, 0)), Optional.empty());
    assertEquals(Verdict.State.GREEN, verdict.state());
    assertTrue(verdict.reasons().isEmpty());

  }

  @Test
  @DisplayName("One more statement per start is red at once")
  public void oneMoreStatementIsRed() {

    final var verdict = Verdict.judge(comparison(round(5, 1000, 0), round(6, 1000, 0)), Optional.empty());
    assertEquals(Verdict.State.RED, verdict.state());
    assertTrue(verdict.reasons().getFirst().contains("statements.start moved from 5 to 6"), verdict.reasons()
        .toString());

  }

  @Test
  @DisplayName("A discarded operation is red, however fast the run was")
  public void aDiscardedOperationIsRed() {

    final var verdict = Verdict.judge(comparison(round(5, 1000, 0), round(5, 500, 1)), Optional.empty());
    assertEquals(Verdict.State.RED, verdict.state());
    assertTrue(verdict.reasons().getFirst().contains("outbox.discarded"), verdict.reasons().toString());

  }

  @Test
  @DisplayName("A time beyond the threshold in one week is a note")
  public void oneSlowWeekIsANote() {

    final var verdict = Verdict
        .judge(comparison(round(5, 1000, 0), round(5, 1200, 0)), Optional.of(Map.of("wall.ms.ratio", "1.02")));
    assertEquals(Verdict.State.NOTE, verdict.state());

  }

  @Test
  @DisplayName("A time beyond the threshold in two weeks in a row is red")
  public void twoSlowWeeksAreRed() {

    final var verdict = Verdict
        .judge(comparison(round(5, 1000, 0), round(5, 1200, 0)), Optional.of(Map.of("wall.ms.ratio", "1.15")));
    assertEquals(Verdict.State.RED, verdict.state());
    assertTrue(verdict.reasons().getFirst().contains("two weeks in a row"), verdict.reasons().toString());

  }

  @Test
  @DisplayName("A faster week is green, whatever the week before said")
  public void aFasterWeekIsGreen() {

    final var verdict = Verdict
        .judge(comparison(round(5, 1000, 0), round(5, 800, 0)), Optional.of(Map.of("wall.ms.ratio", "1.3")));
    assertEquals(Verdict.State.GREEN, verdict.state());

  }

}
