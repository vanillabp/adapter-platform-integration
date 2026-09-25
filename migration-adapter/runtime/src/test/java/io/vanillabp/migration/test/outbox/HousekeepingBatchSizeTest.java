package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.outbox.HousekeepingBatchSize;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How many rows one housekeeping batch takes on. Nobody can name that number in advance,
 * so the rule finds it by measuring - and what it does with a measurement is what these
 * tests hold.
 */
@ExtendWith(SuppressOutputExtension.class)
public class HousekeepingBatchSizeTest {

  @Test
  @DisplayName("The first batch of the first night is the size the rule starts from")
  public void theFirstBatchIsTheStartingSize() {

    assertEquals(HousekeepingBatchSize.FIRST_BATCH, new HousekeepingBatchSize().size());

  }

  @Test
  @DisplayName("A batch which leaves room for twice its time doubles the next one")
  public void aBatchWithRoomToSpareDoubles() {

    final var rule = new HousekeepingBatchSize();

    rule.aBatchFitted(Duration.ofSeconds(1), Duration.ofMinutes(10));

    assertEquals(HousekeepingBatchSize.FIRST_BATCH * 2, rule.size());

  }

  @Test
  @DisplayName("A batch whose double would not fit keeps the size it had")
  public void aBatchWithoutRoomStaysAsItIs() {

    final var rule = new HousekeepingBatchSize();

    rule.aBatchFitted(Duration.ofSeconds(10), Duration.ofSeconds(15));

    assertEquals(HousekeepingBatchSize.FIRST_BATCH, rule.size(), "twice ten seconds does not fit into fifteen");

  }

  @Test
  @DisplayName("The doubling stops at the largest batch the rule allows")
  public void theRampIsCapped() {

    final var rule = new HousekeepingBatchSize();

    for (var round = 0; round < 40; round++) {
      rule.aBatchFitted(Duration.ofMillis(1), Duration.ofHours(1));
    }

    assertEquals(
        HousekeepingBatchSize.LARGEST_BATCH,
        rule.size(),
        "on a store whose time grows with the table rather than with the batch this would climb forever");

  }

  @Test
  @DisplayName("A batch which ran past the end of the window halves the next one")
  public void aBatchWhichOverranHalves() {

    final var rule = new HousekeepingBatchSize();

    rule.aBatchOverran();

    assertEquals(
        HousekeepingBatchSize.FIRST_BATCH / 2,
        rule.size(),
        "one wrong step must not cost the rest of the night");

  }

  @Test
  @DisplayName("The next night starts at half of the largest batch which fitted")
  public void theNextNightStartsAtHalfOfWhatFitted() {

    final var rule = new HousekeepingBatchSize();
    rule.aBatchFitted(Duration.ofSeconds(1), Duration.ofMinutes(10));
    rule.aBatchFitted(Duration.ofSeconds(1), Duration.ofMinutes(10));
    final var largestThatFitted = rule.size() / 2;
    assertTrue(
        largestThatFitted > HousekeepingBatchSize.FIRST_BATCH,
        "the ramp did not climb, so this test would prove nothing");

    rule.theWindowClosed();

    assertEquals(
        largestThatFitted / 2,
        rule.size(),
        "the database changed over night, and half is the distance kept from a measurement a day old");

  }

  @Test
  @DisplayName("A night in which nothing fitted starts the next one from the beginning")
  public void aNightWithoutAFittingBatchStartsOver() {

    final var rule = new HousekeepingBatchSize();
    rule.aBatchOverran();

    rule.theWindowClosed();

    assertEquals(HousekeepingBatchSize.FIRST_BATCH, rule.size());

  }

  @Test
  @DisplayName("The size never falls below one, however often a batch overran")
  public void theSizeNeverFallsBelowOne() {

    final var rule = new HousekeepingBatchSize();

    for (var round = 0; round < 40; round++) {
      rule.aBatchOverran();
    }

    assertEquals(1, rule.size(), "a batch of no rows would remove nothing and the sweep would never end");

  }

}
