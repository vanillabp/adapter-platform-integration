package io.vanillabp.integration.test.apptx;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The application's own phase-two outbox: the entry rides the unit of work of the
 * application, and the dispatch happens right after its commit - the contract of
 * {@link PhaseTwoOutbox} fulfilled without any database.
 */
@ApplicationScoped
public class AppTxOutbox implements PhaseTwoOutbox {

  @Inject
  PhaseTwoRouter router;

  @Inject
  AppTxTransactionRunner runner;

  private final List<PhaseTwoCall> scheduled = new CopyOnWriteArrayList<>();

  public List<PhaseTwoCall> getScheduled() {

    return scheduled;

  }

  public void clear() {

    scheduled.clear();

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    final var idempotencyKey = call
        .idempotencyKey()
        .orElse(null);
    if ((idempotencyKey != null) && scheduled
        .stream()
        .anyMatch(entry -> idempotencyKey
            .equals(
                entry
                    .idempotencyKey()
                    .orElse(null)))) {
      return false;
    }
    scheduled.add(call);
    runner.afterCommit(() -> router.dispatch(call));
    return true;

  }

}
