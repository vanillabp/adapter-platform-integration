package io.vanillabp.integration.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A {@link PhaseTwoOutbox} defined by the application, which records the scheduled calls.
 * It is the test double for a dedicated outbox store which {@link DedicatedOutboxAware}
 * assigns to a "hot" process.
 */
@ApplicationScoped
public class DedicatedOutbox implements PhaseTwoOutbox {

  private final List<PhaseTwoCall> scheduledCalls = new CopyOnWriteArrayList<>();

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    scheduledCalls.add(call);
    return true;

  }

  public List<PhaseTwoCall> getScheduledCalls() {

    return List.copyOf(scheduledCalls);

  }

}
