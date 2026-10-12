package io.vanillabp.integration.test.sample;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.inject.Singleton;

/**
 * The store this application needs to boot. Everything which a workflow module sends to
 * its BPMS is planned in the caller's transaction and dispatched after the commit. So the
 * platform demands a store it can resolve at startup. It also demands one from an
 * application whose BPMS is the dummy adapter and which is only asked which workflow
 * module its process services belong to. This one remembers nothing and dispatches nothing.
 */
@Singleton
public class SampleOutbox implements PhaseTwoOutbox {

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    return true;

  }

}
