package io.vanillabp.migration.test;

import static org.mockito.Mockito.lenient;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;

/**
 * Gives a mocked adapter the phase operations every adapter has to answer.
 * <p>
 * Mockito answers a default method the way it answers every other one - with
 * <code>null</code> - so a mocked adapter would contribute no operation at all and the
 * core would refuse every call to it. What the mock answers here writes down what it was
 * asked, which is what a test wants to know: the map is what the core calls.
 */
public final class AdapterMocks {

  private AdapterMocks() {
  }

  /**
   * @param <A> The workflow-aggregate type
   * @param adapter A mocked adapter
   * @return What the adapter was asked to do, to assert against
   */
  public static <A> RecordedPhaseOperations<A> recordingItsOperations(
      final MigratableProcessService<A> adapter) {

    final var recorded = new RecordedPhaseOperations<A>();
    lenient()
        .when(adapter.phaseOperations())
        .thenReturn(recorded.operations());
    return recorded;

  }

}
