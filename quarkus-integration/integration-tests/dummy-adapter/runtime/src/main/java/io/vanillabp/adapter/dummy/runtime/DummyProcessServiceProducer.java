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
 * For testing the two-phase workflow start, the property
 * <code>dummy-adapter.two-phase-commit</code> forces
 * {@link MigratableProcessService#needsTwoPhaseCommitForStartingWorkflows()} to return
 * <code>true</code>, and optional {@link DummyPhaseTwoListener} beans are notified on
 * phase two.
 */
@ApplicationScoped
public class DummyProcessServiceProducer {

  public static final String ADAPTER_TYPE = "dummy";

  /**
   * The property forcing the dummy adapter to require a two-phase commit for starting
   * workflows. Used by integration tests of
   * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox} implementations.
   */
  public static final String PROPERTY_TWO_PHASE_COMMIT = "dummy-adapter.two-phase-commit";

  @Produces
  public List<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>> dummyMigratableProcessServices(
      final MigrationAdapterProperties properties,
      @Any final Instance<DummyPhaseTwoListener> phaseTwoListeners) {

    final var needsTwoPhaseCommit = ConfigProvider
        .getConfig()
        .getOptionalValue(PROPERTY_TWO_PHASE_COMMIT, Boolean.class)
        .orElse(Boolean.FALSE);

    return properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted().<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>>map(
            adapterId -> new MigratableProcessService<>(adapterId, needsTwoPhaseCommit, phaseTwoListeners))
        .toList();

  }

}
