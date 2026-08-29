package io.vanillabp.migration.test;

import static org.mockito.Mockito.lenient;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;

/**
 * Gives a mocked adapter the phase operations the SPI hands every adapter.
 * <p>
 * Mockito answers a default method the way it answers every other one - with
 * <code>null</code> - so a mocked adapter would contribute no operation at all and the
 * core would refuse every call to it. The two stubs below restore the SPI's own answer,
 * the handlers bridging to the pair of methods per operation, which is what a test
 * verifying &quot;the core reached the adapter&quot; wants.
 */
public final class AdapterMocks {

  private AdapterMocks() {
  }

  /**
   * @param <A> The workflow-aggregate type
   * @param adapter A mocked adapter
   * @return The same mock, now serving the core operations
   */
  public static <A> MigratableProcessService<A> servingItsOperations(
      final MigratableProcessService<A> adapter) {

    lenient()
        .when(adapter.phaseOperations())
        .thenCallRealMethod();
    lenient()
        .when(adapter.legacyPhaseOperations())
        .thenCallRealMethod();
    return adapter;

  }

}
