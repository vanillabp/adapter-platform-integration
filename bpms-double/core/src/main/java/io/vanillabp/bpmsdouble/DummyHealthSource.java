package io.vanillabp.bpmsdouble;

import io.vanillabp.integration.adapter.spi.health.AdapterHealth;

/**
 * Optional hook of the dummy adapter. Integration tests use it to play what a real
 * adapter finds when it asks its BPMS. Without a bean the adapter adds nothing to the
 * health endpoint, like an adapter without a check. With a bean it adds whatever the
 * test returns. This includes an exception, for the adapter which cannot answer its own
 * question.
 */
@FunctionalInterface
public interface DummyHealthSource {

  /**
   * What the double answers when the platform asks it about the BPMS behind it. A test
   * returns a state to see it arrive at the health endpoint. Or it throws, to play the
   * adapter which cannot even reach its BPMS.
   *
   * @param adapterId The adapter ID being asked, so one bean can answer for several
   *          configured ids
   * @return What the adapter found, or <code>null</code> to contribute nothing
   */
  AdapterHealth healthOf(
      String adapterId);

}
