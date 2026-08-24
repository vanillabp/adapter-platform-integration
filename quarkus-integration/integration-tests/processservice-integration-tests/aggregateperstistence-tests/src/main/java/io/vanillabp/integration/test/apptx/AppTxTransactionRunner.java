package io.vanillabp.integration.test.apptx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.vanillabp.integration.spi.TransactionRunner;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The unit of work of the application, counting what VanillaBP does with it.
 * Also what an outbox needs: work which runs after the commit.
 */
@ApplicationScoped
public class AppTxTransactionRunner implements TransactionRunner {

  private final AtomicInteger opened = new AtomicInteger();

  private final AtomicInteger committed = new AtomicInteger();

  private final AtomicInteger rolledBack = new AtomicInteger();

  private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

  private final ThreadLocal<List<Runnable>> afterCommit = ThreadLocal.withInitial(ArrayList::new);

  public int getOpened() {

    return opened.get();

  }

  public int getCommitted() {

    return committed.get();

  }

  public int getRolledBack() {

    return rolledBack.get();

  }

  public void reset() {

    opened.set(0);
    committed.set(0);
    rolledBack.set(0);

  }

  public void afterCommit(
      final Runnable work) {

    afterCommit.get().add(work);

  }

  @Override
  public <T> T requireNew(
      final Supplier<T> work) {

    opened.incrementAndGet();
    depth.set(depth.get() + 1);
    try {
      final var result = work.get();
      depth.set(depth.get() - 1);
      committed.incrementAndGet();
      if (depth.get() == 0) {
        final var pending = List.copyOf(afterCommit.get());
        afterCommit.get().clear();
        pending.forEach(Runnable::run);
      }
      return result;
    } catch (final RuntimeException failure) {
      depth.set(depth.get() - 1);
      rolledBack.incrementAndGet();
      if (depth.get() == 0) {
        afterCommit.get().clear();
      }
      throw failure;
    }

  }

  @Override
  public <T> T inCurrent(
      final Supplier<T> work) {

    if (depth.get() == 0) {
      throw new IllegalStateException("no unit of work of the application is open");
    }
    return work.get();

  }

  @Override
  public <T> T requireTransaction(
      final Supplier<T> work) {

    return depth.get() > 0
        ? work.get()
        : requireNew(work);

  }

  @Override
  public boolean isTransactionActive() {

    return depth.get() > 0;

  }

  @Override
  public boolean isRollbackOnly() {

    return false;

  }

}
