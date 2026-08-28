package io.vanillabp.adapter.dummy.runtime;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

/**
 * Provides the dummy adapter's {@link MigratableProcessService} instances - the
 * reference implementation of the per-adapter-id bean convention every VanillaBP
 * adapter follows on Quarkus: a CDI producer cannot yield N element beans for N
 * runtime-configured adapter ids, so the adapter produces ONE bean of type
 * <code>List&lt;MigratableProcessService&gt;</code> with one instance PER configured
 * adapter id of its type (multiple ids of one BPMS type = the migration scenario);
 * the platform's collection point flattens List beans alongside element beans. The
 * adapter id is a CONSTRUCTOR parameter of each instance.
 * <p>
 * The adapter-id set ALWAYS comes from the platform's core properties
 * ({@code adapterTypes()}); adapter-owned overlay maps of the
 * <code>vanillabp.*</code> tree are per-known-id lookups only and must never be
 * iterated to discover ids.
 * <p>
 * Optional {@link DummyPhaseTwoListener} beans are notified on phase two. The property
 * <code>dummy-adapter.at-least-once-delivery</code> makes this dummy report the delivery
 * behaviour of a BPMS which repeats a task it did not learn the outcome of
 * ({@link MigratableProcessService#deliversTasksAtLeastOnce()}).
 */
@ApplicationScoped
public class DummyProcessServiceProducer {

  public static final String ADAPTER_TYPE = "dummy";

  /**
   * The property making the dummy adapter report the task delivery of a BPMS which
   * repeats a task it did not learn the outcome of. Used by the tests of the delivery
   * record and of {@link io.vanillabp.integration.spi.PhaseTwoOutbox} implementations.
   */
  public static final String PROPERTY_AT_LEAST_ONCE_DELIVERY = "dummy-adapter.at-least-once-delivery";

  /**
   * The property making the dummy adapter READ the workflow aggregate in phase two,
   * the way an adapter of a remote BPMS does (it builds the variables it sends from
   * the aggregate). Off by default, because most test doubles of
   * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} implement nothing
   * but save; the tests of the phase-two contract switch it on.
   */
  public static final String PROPERTY_READ_AGGREGATE_IN_PHASE_TWO = "dummy-adapter.read-aggregate-in-phase-two";

  @Produces
  public List<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>> dummyMigratableProcessServices(
      final MigrationAdapterProperties properties,
      @Any final Instance<DummyPhaseTwoListener> phaseTwoListeners,
      @Any final Instance<DummyTaskAwarenessSource> taskAwarenessSources,
      @Any final Instance<DummyViewerSource> viewerSources) {

    final var deliversTasksAtLeastOnce = ConfigProvider
        .getConfig()
        .getOptionalValue(PROPERTY_AT_LEAST_ONCE_DELIVERY, Boolean.class)
        .orElse(Boolean.FALSE);
    final var readsAggregateInPhaseTwo = ConfigProvider
        .getConfig()
        .getOptionalValue(PROPERTY_READ_AGGREGATE_IN_PHASE_TWO, Boolean.class)
        .orElse(Boolean.FALSE);

    return properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted().<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>>map(
            adapterId -> new MigratableProcessService<>(adapterId, deliversTasksAtLeastOnce, readsAggregateInPhaseTwo, phaseTwoListeners, taskAwarenessSources, viewerSources))
        .toList();

  }

}
