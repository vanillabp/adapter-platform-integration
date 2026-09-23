package io.vanillabp.bpmsdouble;

import io.vanillabp.integration.adapter.spi.health.AdapterHealth;

/**
 * Optional hook of the dummy adapter used by integration tests to play what a real
 * adapter finds when it asks its BPMS: without a bean the adapter contributes
 * nothing to the health endpoint (which is what an adapter without a check does),
 * with one it contributes whatever the test returns - including an exception, to
 * cover the adapter which cannot answer its own question.
 */
@FunctionalInterface
public interface DummyHealthSource {

  /**
   * What the double answers when the platform asks it about the BPMS behind it. A
   * test returns a state to see it arrive at the health endpoint, or throws to play
   * the adapter which cannot even reach its BPMS.
   *
   * @param adapterId The adapter ID being asked, so one bean can answer for several
   *          configured ids
   * @return What the adapter found, or <code>null</code> to contribute nothing
   */
  AdapterHealth healthOf(
      String adapterId);

}
