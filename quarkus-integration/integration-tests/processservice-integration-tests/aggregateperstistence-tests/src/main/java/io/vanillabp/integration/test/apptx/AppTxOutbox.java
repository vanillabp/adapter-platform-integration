package io.vanillabp.integration.test.apptx;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The application's own phase-two outbox. An entry joins the unit of work of the
 * application, and the dispatch happens right after its commit. So it keeps the contract of
 * {@link PhaseTwoOutbox} without any database.
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

    // Deliberately simpler than a real store. Nothing here is ever marked done, so a key
    // deduplicates for as long as this application lives. A real store deduplicates only
    // the entries which still wait for their dispatch (see PhaseTwoOutbox). These tests
    // are about which store serves an aggregate.
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
