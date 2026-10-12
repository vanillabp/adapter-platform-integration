package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.outbox.DispatchLanes;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The two promises an outbox makes once it dispatches on more than one thread. What belongs
 * to one workflow aggregate keeps its order. What belongs to different ones runs at the
 * same time. The test also covers what the handover answers. That is how a caller learns
 * that a stopping lane will never run its work.
 * <p>
 * The test asserts both here and not over a database. A test which starts workflows and
 * waits for a BPMS double proves neither. A correct implementation and one which quietly
 * runs everything one after the other look alike from the outside. And two entries which
 * happen to be dispatched in order prove nothing about the next run. The integration tests
 * of the stores add that the lanes are really wired in.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DispatchLanesTest {

  /**
   * How long a test waits for work which has to arrive. It measures nothing. Every claim
   * below is read from what the lanes did, not from how fast they did it. So a loaded
   * machine makes this test slower, not red.
   */
  private static final long UNTIL_A_LANE_COUNTS_AS_STUCK = 30;

  private DispatchLanes lanes;

  @AfterEach
  public void stopTheLanes() {

    if (lanes != null) {
      lanes.stop();
      lanes = null;
    }

  }

  @Test
  @DisplayName("What one aggregate wrote arrives in the order it was written")
  public void oneAggregateKeepsItsOrder() throws Exception {

    lanes = new DispatchLanes("test-order", 4);
    final var arrived = new CopyOnWriteArrayList<Integer>();
    final var allArrived = new CountDownLatch(200);

    // one key, handed in by one thread. The whole question is what the lanes do with it.
    // 200 of them leave no room for a lucky run
    IntStream
        .range(0, 200)
        .forEach(entry -> lanes.runInOrderOf("aggregate-1", () -> {
          arrived.add(entry);
          allArrived.countDown();
        }));

    assertTrue(
        allArrived.await(UNTIL_A_LANE_COUNTS_AS_STUCK, TimeUnit.SECONDS),
        "the lanes did not dispatch everything they were handed");
    assertEquals(
        IntStream.range(0, 200).boxed().toList(),
        List.copyOf(arrived),
        "two entries of one aggregate overtook each other");

  }

  @Test
  @DisplayName("Two aggregates really are dispatched at the same time")
  public void twoAggregatesRunAtTheSameTime() throws Exception {

    lanes = new DispatchLanes("test-concurrency", 4);
    final var keys = twoKeysOfDifferentLanes(4);
    // each of the two waits for the other to start. A store which dispatches one after the
    // other never gets past the first of them. So this test tells a working implementation
    // from one which only looks like it
    final var bothStarted = new CountDownLatch(2);
    final var bothEnded = new CountDownLatch(2);

    keys
        .forEach(key -> lanes.runInOrderOf(key, () -> {
          try {
            bothStarted.countDown();
            if (bothStarted.await(UNTIL_A_LANE_COUNTS_AS_STUCK, TimeUnit.SECONDS)) {
              bothEnded.countDown();
            }
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }));

    assertTrue(
        bothEnded.await(UNTIL_A_LANE_COUNTS_AS_STUCK, TimeUnit.SECONDS),
        "the two aggregates were dispatched one after the other");

  }

  @Test
  @DisplayName("A backlog waits in the queue instead of being run by whoever handed it in")
  public void aFullQueueMakesTheCallerWait() throws Exception {

    // one lane, so everything meets the same queue. The first entry holds it until the test
    // lets go. A lane which let the caller run the work would break the order. So the caller
    // has to wait, and the thread names below show that
    lanes = new DispatchLanes("test-backpressure", 1);
    final var holdTheLane = new CountDownLatch(1);
    final var ranOn = new CopyOnWriteArrayList<String>();
    final var allArrived = new CountDownLatch(40);

    lanes.runInOrderOf("aggregate-1", () -> {
      try {
        holdTheLane.await(UNTIL_A_LANE_COUNTS_AS_STUCK, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      ranOn.add(Thread.currentThread().getName());
      allArrived.countDown();
    });
    final var handingIn = new Thread(() -> IntStream
        .range(0, 39)
        .forEach(entry -> lanes.runInOrderOf("aggregate-1", () -> {
          ranOn.add(Thread.currentThread().getName());
          allArrived.countDown();
        })));
    handingIn.start();

    holdTheLane.countDown();
    assertTrue(
        allArrived.await(UNTIL_A_LANE_COUNTS_AS_STUCK, TimeUnit.SECONDS),
        "the lane did not dispatch everything it was handed");
    handingIn.join();
    assertEquals(
        List.of("test-backpressure-0"),
        ranOn.stream().distinct().toList(),
        "something ran outside the lane of its aggregate");

  }

  @Test
  @DisplayName("A lane which is stopping says that it took nothing")
  public void aStoppingLaneSaysThatItTookNothing() {

    lanes = new DispatchLanes("test-shutdown", 1);
    final var ran = new AtomicBoolean();
    // the lane which gets the work below has stopped. A node which shuts down does this
    // while its poller still hands entries in
    lanes.stop();

    final var taken = lanes.runInOrderOf("aggregate-1", () -> ran.set(true));

    assertFalse(taken, "the caller was told that a stopped lane had taken its work");
    assertFalse(ran.get(), "a stopped lane ran the work it was handed");

  }

  @Test
  @DisplayName("A lane which is running says that it took the work")
  public void aRunningLaneSaysThatItTookTheWork() throws Exception {

    lanes = new DispatchLanes("test-accepted", 1);
    final var ranIt = new CountDownLatch(1);

    final var taken = lanes.runInOrderOf("aggregate-1", ranIt::countDown);

    assertTrue(taken, "the caller was told that the lane had not taken its work");
    assertTrue(
        ranIt.await(UNTIL_A_LANE_COUNTS_AS_STUCK, TimeUnit.SECONDS),
        "the lane said it took the work and never ran it");

  }

  /**
   * Two keys which the lanes send to different threads. The implementation decides which
   * key lands where. So the test asks it instead of assuming it.
   *
   * @param count How many lanes there are
   * @return Two keys of two different lanes
   */
  private static List<String> twoKeysOfDifferentLanes(
      final int count) {

    final var keys = new ArrayList<String>();
    final var lanesUsed = new ArrayList<Integer>();
    for (var candidate = 0; keys.size() < 2; candidate++) {
      final var key = "aggregate-"
          + candidate;
      final var lane = DispatchLanes.laneOf(key, count);
      if (!lanesUsed.contains(lane)) {
        lanesUsed.add(lane);
        keys.add(key);
      }
    }
    return keys;

  }

}
