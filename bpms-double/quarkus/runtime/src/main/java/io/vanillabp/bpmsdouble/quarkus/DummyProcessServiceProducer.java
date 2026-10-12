package io.vanillabp.bpmsdouble.quarkus;

import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;

import io.vanillabp.bpmsdouble.DummyAdapter;
import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.bpmsdouble.DummyProcessService;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyViewerSource;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

/**
 * Provides the double's {@link DummyProcessService} instances. It is the reference for
 * the bean rule every VanillaBP adapter follows on Quarkus, one instance per adapter id.
 * A CDI producer cannot create N element beans for N adapter ids configured at runtime.
 * So the adapter produces ONE bean of type <code>List&lt;MigratableProcessService&gt;</code>,
 * with one instance PER configured adapter id of its type. Several ids of one BPMS type
 * are the migration scenario. The platform's collection point flattens List beans next
 * to element beans. The adapter id is a CONSTRUCTOR parameter of each instance.
 * <p>
 * The set of adapter ids ALWAYS comes from the platform's core properties, from
 * {@code adapterIdsOfType()}. The whole rule lives there. Filtering {@code adapterTypes()}
 * would lose two kinds of ids: those an application names in
 * <code>prioritized-adapters</code> without a section, and the id which an application
 * without configuration gets from the classpath. Overlay maps which an adapter owns in
 * the <code>vanillabp.*</code> tree are only looked up by ids already known. Never
 * iterate them to find ids.
 * <p>
 * Optional {@link DummyPhaseTwoListener} beans are notified in phase two. The
 * properties of {@link DummyAdapter} switch on two behaviours which a real BPMS has and
 * a logging double would not.
 */
@ApplicationScoped
public class DummyProcessServiceProducer {

  /**
   * See {@link DummyAdapter#ADAPTER_TYPE}.
   */
  public static final String ADAPTER_TYPE = DummyAdapter.ADAPTER_TYPE;

  /**
   * See {@link DummyAdapter#PROPERTY_AT_LEAST_ONCE_DELIVERY}.
   */
  public static final String PROPERTY_AT_LEAST_ONCE_DELIVERY = DummyAdapter.PROPERTY_AT_LEAST_ONCE_DELIVERY;

  /**
   * See {@link DummyAdapter#PROPERTY_READ_AGGREGATE_IN_PHASE_TWO}.
   */
  public static final String PROPERTY_READ_AGGREGATE_IN_PHASE_TWO = DummyAdapter.PROPERTY_READ_AGGREGATE_IN_PHASE_TWO;

  /**
   * CDI builds this producer once per application. A test never creates it and never
   * calls the method below. It asks the container for the process services, or lets the
   * core's election do it.
   */
  public DummyProcessServiceProducer() {
  }

  /**
   * Builds one {@link DummyProcessService} per configured adapter id of the dummy type,
   * as the one List bean described above. The two switches are read from the
   * configuration here. So every instance of one application start behaves the same way.
   *
   * @param properties The platform's core properties, the only place the adapter ids
   *          come from
   * @param phaseTwoListeners The hooks a test declared to watch phase two
   * @param taskAwarenessSources The hooks answering what this BPMS knows about
   * @param viewerSources The hooks answering the viewer API
   * @return One process service per configured adapter id of the dummy type
   */
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
        .adapterIdsOfType(ADAPTER_TYPE)
        .stream().<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>>map(
            adapterId -> new DummyProcessService<>(
                adapterId, deliversTasksAtLeastOnce, readsAggregateInPhaseTwo, phaseTwoListeners::stream, taskAwarenessSources::stream, viewerSources::stream))
        .toList();

  }

}
