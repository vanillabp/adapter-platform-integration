package io.vanillabp.integration.test.adapter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The store for tests which are not about the store. Everything an application sends to
 * its BPMS is planned in the caller's transaction and dispatched after it committed. So an
 * application without a store cannot start a workflow, and the platform says so while it
 * boots. A test whose archive brings neither a datasource nor a MongoDB client adds this
 * one. It remembers what was planned and dispatches nothing. That is what a test about
 * configuration, discovery or wiring wants for a phase two.
 */
@ApplicationScoped
@Unremovable
public class TestPhaseTwoOutbox implements PhaseTwoOutbox {

  private final List<PhaseTwoCall> planned = new CopyOnWriteArrayList<>();

  /**
   * @return What was planned so far, in order
   */
  public List<PhaseTwoCall> planned() {

    return List.copyOf(planned);

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    planned.add(call);
    return true;

  }

}
