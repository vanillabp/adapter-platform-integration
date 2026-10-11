package io.vanillabp.integration.adapter.migration.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * The delivery keys of the open tasks which a BPMS redelivered since the last refresh. They
 * are kept until the store writes them in one go. Every
 * {@link io.vanillabp.integration.spi.TaskDeliveryLog} implementation of both platforms
 * shares this class. They differ in HOW a record is refreshed, not in when and in which
 * portions.
 * <p>
 * A <code>&#64;TaskId</code> handler may leave a task open. Then the BPMS redelivers it as
 * often as it renews its lock. The record written when the handler ran answers every
 * redelivery. So the record has to outlive the retention as long as redeliveries keep
 * coming. The second timestamp of a record is for that. Writing it on every redelivery would
 * put an UPDATE into the transaction of every lock renewal of every open task. So the key
 * is remembered here instead. The timer which already runs the retention cleanup writes
 * what has accumulated.
 * <p>
 * Refreshing happens in blocks of {@value #BLOCK_SIZE} keys, with one statement per block and
 * not one <code>IN</code> list. Every database limits the length of such a list differently
 * (Oracle at 1000 expressions, SQL Server at about 2100 parameters). A batch of the same
 * statement has no such limit.
 * <p>
 * If a crash loses the memory, that costs one interval of refreshes. A record only expires
 * when a whole retention period passes without a single refresh. That is also why the memory
 * is limited to {@value #MAX_REMEMBERED} keys. It is a hint, not a fact which must be kept
 * safe.
 * <p>
 * Decision 6 in the repository's DECISIONS.md says why a redelivery refreshes a record
 * instead of writing one, and why that second timestamp exists at all.
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
   * Built by the store whose records are refreshed, once per application.
   * <p>
   * The writing is handed in and not done here, because the two platforms differ in how a
   * record is refreshed, and in nothing else.
   *
   * @param name Names the refreshed store (log messages)
   * @param refresh Writes the second timestamp of the records of one block of keys
   */
  public OpenTaskTouches(
      final String name,
      final Consumer<List<String>> refresh) {

    this.name = name;
    this.refresh = refresh;

  }

  /**
   * Remembers that the record of this delivery still answers redeliveries.
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
                refreshed for a whole '{}' is deleted although its task is \
                still open, so its next redelivery reaches the @WorkflowTask method again. Either \
                that many tasks are open at once and the retention should be raised, or tasks are \
                waiting which nobody will ever complete - '{}' reports \
                those.""",
            MAX_REMEMBERED,
            name,
            DeliveryProperties.RETENTION_PROPERTY,
            DeliveryProperties.MAX_TASK_AGE_PROPERTY);
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
   * Writes what has accumulated, in blocks. Keys are taken out of the memory as they are
   * collected. So a redelivery which arrives while this runs is remembered for the next round
   * and not lost.
   * <p>
   * A failing block is logged, and its keys are gone. The next redelivery of that task
   * remembers it again. The record survives as long as one refresh per retention period gets
   * through.
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
