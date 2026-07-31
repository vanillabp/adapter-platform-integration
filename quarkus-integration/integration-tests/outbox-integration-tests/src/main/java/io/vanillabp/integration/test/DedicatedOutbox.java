package io.vanillabp.integration.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.integration.adapter.spi.PhaseTwoCall;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * An application-defined {@link PhaseTwoOutbox} recording scheduled calls - the test
 * double for a dedicated outbox store assigned to a "hot" process via
 * {@link DedicatedOutboxAware}.
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
