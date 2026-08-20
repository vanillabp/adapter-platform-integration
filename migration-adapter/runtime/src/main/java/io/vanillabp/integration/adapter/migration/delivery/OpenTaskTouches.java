package io.vanillabp.integration.adapter.migration.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

/**
 * The delivery keys of the open tasks a BPMS redelivered since the last refresh, kept
 * until the store writes them in one go (story 97). Shared by every
 * {@link io.vanillabp.integration.spi.TaskDeliveryLog} implementation of both platforms,
 * because they differ in HOW a record is refreshed, not in when and in which portions.
 * <p>
 * A task left open by a <code>&#64;TaskId</code> handler is redelivered as often as the
 * BPMS renews its lock, and every redelivery is answered from the record written when the
 * handler ran. The record therefore has to outlive the retention as long as redeliveries
 * keep coming, which is what the second timestamp of a record is for. Writing it per
 * redelivery would put an UPDATE into the transaction of every renewal of every open
 * task, so the key is remembered here instead and the timer which already runs the
 * retention cleanup writes what accumulated.
 * <p>
 * Refreshing happens in blocks of {@value #BLOCK_SIZE} keys, one statement per block
 * rather than one <code>IN</code> list: the length of such a list is capped differently
 * by every database (Oracle at 1000 expressions, SQL Server at about 2100 parameters), and
 * a batch of the same statement has no such limit.
 * <p>
 * Losing the memory to a crash costs one interval of refreshments, because a record only
 * expires when a whole retention passes without a single one. That is also why the memory
 * is bounded at {@value #MAX_REMEMBERED} keys: it is a hint, not a fact to be kept safe.
 */
@Slf4j
public class OpenTaskTouches {

  /**
   * How many keys are written per statement execution.
   */
  public static final int BLOCK_SIZE = 500;

  /**
   * How many keys are remembered at most between two refreshes.
   */
  public static final int MAX_REMEMBERED = 100000;

  private final String name;

  private final Consumer<List<String>> refresh;

  private final java.util.Set<String> keys = ConcurrentHashMap.newKeySet();

  private final AtomicBoolean overflowReported = new AtomicBoolean();

  /**
   * @param name Names the store refreshed (log messages)
   * @param refresh Writes the second timestamp of the records of one block of keys
   */
  public OpenTaskTouches(
      final String name,
      final Consumer<List<String>> refresh) {

    this.name = name;
    this.refresh = refresh;

  }

  /**
   * Remembers that the record of this delivery is still answering redeliveries.
   *
   * @param deliveryKey The delivery's identity
   */
  public void remember(
      final String deliveryKey) {

    if (deliveryKey == null) {
      return;
    }
    if (keys.size() >= MAX_REMEMBERED) {
      if (overflowReported.compareAndSet(false, true)) {
        log.warn(
            """
                More than {} open tasks of '{}' were redelivered since the last refresh of their \
                delivery records - the keys beyond that are dropped, and a record which is not \
                refreshed for a whole 'vanillabp.outbox.retention' is deleted although its task is \
                still open, so its next redelivery reaches the @WorkflowTask method again. Either \
                that many tasks are open at once and the retention should be raised, or tasks are \
                waiting which nobody will ever complete - 'vanillabp.delivery.max-task-age' reports \
                those.""",
            MAX_REMEMBERED,
            name);
      }
      return;
    }
    keys.add(deliveryKey);

  }

  /**
   * How many keys are waiting to be written.
   *
   * @return The number of keys remembered
   */
  public int size() {

    return keys.size();

  }

  /**
   * Writes what accumulated, in blocks. Keys are taken out of the memory as they are
   * collected, so a redelivery arriving while this runs is remembered for the next
   * round rather than lost.
   * <p>
   * A failing block is logged and its keys are gone: the next redelivery of that task
   * remembers it again, and the record survives as long as one refresh per retention
   * period gets through.
   *
   * @return The number of records refreshed
   */
  public int flush() {

    if (keys.isEmpty()) {
      return 0;
    }
    final var block = new ArrayList<String>(BLOCK_SIZE);
    var refreshed = 0;
    try {
      for (final var iterator = keys.iterator(); iterator.hasNext();) {
        block.add(iterator.next());
        iterator.remove();
        if (block.size() < BLOCK_SIZE) {
          continue;
        }
        refresh.accept(List.copyOf(block));
        refreshed += block.size();
        block.clear();
      }
      if (!block.isEmpty()) {
        refresh.accept(List.copyOf(block));
        refreshed += block.size();
      }
    } catch (final RuntimeException e) {
      log.warn(
          "Could not refresh the delivery records of {} open tasks of '{}'",
          block.size(),
          name,
          e);
    }
    overflowReported.set(false);
    log.debug("Refreshed the delivery records of {} open tasks of '{}'", refreshed, name);
    return refreshed;

  }

}
