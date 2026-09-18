package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.outbox.DueEntryPoller;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The schedule every outbox store shares: it asks when the store owes something and sleeps
 * until then, which is what lets an application with nothing to do leave its database
 * alone.
 * <p>
 * The four stores differ in how they read that moment and not in what happens to it, so the
 * rules are pinned here, in milliseconds, instead of four times over a database. What each
 * store does cost a quiet application is counted in statements by the integration test of
 * that store.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DueEntryPollerTest {

  /**
   * How long a wait for polls goes on before the test gives up. It guards against a poller
   * which stopped and it measures nothing: every timing this class claims is read from the
   * moments the polls happened, so a slow machine makes a test slower rather than red.
   * <p>
   * Five seconds did not do that job. A machine carrying five builds can leave the test JVM
   * without a turn for five seconds. The wait then ends with one poll in hand while the
   * poller has done nothing wrong. Measured on 2026-09-18, with the test JVM stopped for 4.6
   * seconds at a time: the five second wait was red in seven of ten runs of this class, this
   * wait in none of them.
   */
  private static final long UNTIL_A_POLLER_COUNTS_AS_STOPPED = 30000;

  private DueEntryPoller poller;

  private final CopyOnWriteArrayList<Instant> polls = new CopyOnWriteArrayList<>();

  /**
   * What the store answers when it is asked for its earliest unfinished entry.
   */
  private final AtomicReference<Instant> earliestDueAt = new AtomicReference<>();

  @AfterEach
  public void stopThePoller() {

    if (poller != null) {
      poller.stop();
      poller = null;
    }

  }

  private DueEntryPoller aPollerSleepingAtMost(
      final Duration longestSleep) {

    poller = new DueEntryPoller(
        "vanillabp-outbox-test", longestSleep, () -> polls.add(Instant.now()), earliestDueAt::get);
    return poller;

  }

  private void awaitPolls(
      final int count) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + UNTIL_A_POLLER_COUNTS_AS_STOPPED;
    while (polls.size() < count) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected at least %d poll(s), saw %s".formatted(count, whenThePollsHappened()));
      Thread.sleep(10);
    }

  }

  /**
   * How far each poll was from the first one, so a red run says what the poller did rather
   * than only how many times it did it.
   *
   * @return The moments, relative to the first poll
   */
  private String whenThePollsHappened() {

    if (polls.isEmpty()) {
      return "no poll at all";
    }
    final var first = polls.get(0);
    return polls
        .stream()
        .map(poll -> "+%dms".formatted(Duration.between(first, poll).toMillis()))
        .collect(Collectors.joining(", "));

  }

  @Test
  @DisplayName("A store which owes nothing is asked again after the cap and not before")
  public void aStoreWhichOwesNothingIsLeftAloneUntilTheCap() throws Exception {

    // nothing is owed, so the only thing which brings the poller back is the cap - which
    // is there for work a node wrote down before it went away
    earliestDueAt.set(null);
    aPollerSleepingAtMost(Duration.ofSeconds(30)).start();

    awaitPolls(1);
    Thread.sleep(500);
    assertEquals(1, polls.size(), "a poller with nothing to do must not come back within the cap");

  }

  @Test
  @DisplayName("The poller comes back when the store says its entry is due")
  public void theNextPollHappensWhenTheEntryIsDue() throws Exception {

    // an hour of cap, so a second poll can only come from the due time below
    earliestDueAt.set(Instant.now().plusMillis(300));
    aPollerSleepingAtMost(Duration.ofHours(1)).start();

    awaitPolls(2);
    final var slept = Duration.between(polls.get(0), polls.get(1));
    assertTrue(
        slept.toMillis() >= 200,
        "the poller came back after %s, which is sooner than the entry was due".formatted(slept));

  }

  @Test
  @DisplayName("An entry due now pulls an hour of sleep forward")
  public void anEntryDueNowShortensTheSleep() throws Exception {

    earliestDueAt.set(null);
    aPollerSleepingAtMost(Duration.ofHours(1)).start();
    awaitPolls(1);

    poller.somethingIsDueAt(Instant.now());

    awaitPolls(2);

  }

  @Test
  @DisplayName("An entry due in an hour pulls nothing forward")
  public void anEntryDueMuchLaterLeavesTheSleepAlone() throws Exception {

    earliestDueAt.set(null);
    aPollerSleepingAtMost(Duration.ofHours(1)).start();
    awaitPolls(1);

    poller.somethingIsDueAt(Instant.now().plus(Duration.ofHours(1)));

    Thread.sleep(500);
    assertEquals(1, polls.size(), "a notification about an entry due later must not wake anybody");

  }

  @Test
  @DisplayName("A store answering 'due now' forever is not asked as fast as the thread can ask")
  public void aStoreAnsweringDueNowForeverIsNotSpunOn() throws Exception {

    // two nodes racing for the same entry can answer this for a moment, and a poller
    // without a floor would turn that moment into a busy loop
    earliestDueAt.set(Instant.now().minus(Duration.ofHours(1)));
    aPollerSleepingAtMost(Duration.ofHours(1)).start();

    awaitPolls(2);
    Thread.sleep(300);
    assertTrue(
        polls.size() < 20,
        "the poller ran %d times in 300ms, which is a spin rather than a schedule".formatted(polls.size()));

  }

  @Test
  @DisplayName("A store which cannot answer is asked again after the cap")
  public void aStoreWhichThrowsIsAskedAgainAfterTheCap() throws Exception {

    final var cap = Duration.ofMillis(300);
    poller = new DueEntryPoller(
        "vanillabp-outbox-test", cap, () -> polls.add(Instant.now()), () -> {
          throw new IllegalStateException("the store cannot be reached");
        });
    poller.start();

    // the cap is what keeps a store with a broken connection being looked at, and the
    // poll itself is where that failure is reported
    awaitPolls(3);

    // the cap has to be what brings the poller back, not the failure: a store which cannot
    // answer would otherwise be asked as fast as the thread can ask. How much later than the
    // cap it came back is not claimed here. A wall clock cannot tell those two apart, a
    // poller which slept too long and a test JVM which did not run at all. The distance is
    // made of two clock readings and comes out a millisecond short of the cap, so the check
    // allows fifty milliseconds of slack
    final var afterTheFirstThrow = Duration.between(polls.get(0), polls.get(1));
    assertTrue(
        afterTheFirstThrow.compareTo(cap.minusMillis(50)) >= 0,
        "the poller came back %dms after the failed question, which is sooner than the cap of %dms"
            .formatted(afterTheFirstThrow.toMillis(), cap.toMillis()));

  }

  @Test
  @DisplayName("Stopping and starting twice are each a no-op")
  public void startingAndStoppingTwiceChangeNothing() throws Exception {

    earliestDueAt.set(null);
    aPollerSleepingAtMost(Duration.ofHours(1)).start();
    poller.start();
    awaitPolls(1);

    poller.stop();
    poller.stop();

    poller.somethingIsDueAt(Instant.now());
    Thread.sleep(300);
    assertEquals(1, polls.size(), "a stopped poller must not be woken by a notification");

  }

}
