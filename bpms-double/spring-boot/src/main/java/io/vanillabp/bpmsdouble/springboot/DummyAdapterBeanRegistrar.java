package io.vanillabp.bpmsdouble.springboot;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import io.vanillabp.bpmsdouble.DummyAdapter;
import io.vanillabp.bpmsdouble.DummyBpmsInitiatedStartSource;
import io.vanillabp.bpmsdouble.DummyDeploymentListener;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyHealthSource;
import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.bpmsdouble.DummyProcessService;
import io.vanillabp.bpmsdouble.DummyProcessVersionSource;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.DummyViewerSource;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;

/**
 * Registers the double's beans, one set per adapter id. It is the reference for the bean
 * rule every VanillaBP adapter follows on Spring Boot. For EACH configured adapter id of
 * the adapter's type, one {@code MigratableProcessService} <i>element</i> bean and one
 * {@code AdapterDeploymentService} <i>element</i> bean are registered. Several ids of
 * one BPMS type are the migration scenario. There are never beans of type
 * {@code List<...>}, because the platform collects element beans through
 * {@code ObjectProvider.stream()}.
 * <p>
 * The set of ids comes from the runtime configuration. So the beans are registered in
 * code ({@link BeanRegistrar} and
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}). The adapter id is a
 * CONSTRUCTOR parameter of each instance. The bean suppliers are lazy: they get other
 * beans through the {@code SupplierContext} only when the bean is created.
 * <p>
 * This artifact promises the names of the two beans. Tests get the deployment service
 * from the container by its name, to play the BPMS. See the readme of the
 * <code>bpms-double</code> module.
 */
public class DummyAdapterBeanRegistrar implements BeanRegistrar {

  /**
   * Spring builds the registrar once, because the auto-configuration imports it. A test
   * neither creates it nor calls it. It looks up the registered beans by their names.
   */
  public DummyAdapterBeanRegistrar() {
  }

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    final var deliversTasksAtLeastOnce = Boolean.TRUE.equals(
        environment.getProperty(DummyAdapter.PROPERTY_AT_LEAST_ONCE_DELIVERY, Boolean.class, Boolean.FALSE));
    final var readsAggregateInPhaseTwo = Boolean.TRUE.equals(
        environment.getProperty(DummyAdapter.PROPERTY_READ_AGGREGATE_IN_PHASE_TWO, Boolean.class, Boolean.FALSE));

    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(
        environment,
        DummyAdapter.ADAPTER_TYPE,
        adapterId -> {

          registry.registerBean(
              "DummyAdapter_ProcessService_%s".formatted(adapterId),
              DummyProcessService.class,
              spec -> spec.supplier(supplierContext -> new DummyProcessService<>(
                  adapterId, deliversTasksAtLeastOnce, readsAggregateInPhaseTwo, supplierContext
                      .beanProvider(DummyPhaseTwoListener.class)::stream, supplierContext
                          .beanProvider(DummyTaskAwarenessSource.class)::stream, supplierContext
                              .beanProvider(DummyViewerSource.class)::stream)));

          registry.registerBean(
              "DummyAdapter_DeploymentService_%s".formatted(adapterId),
              DummyDeploymentService.class,
              spec -> spec.supplier(supplierContext -> new DummyDeploymentService(
                  adapterId, supplierContext
                      .beanProvider(DummyDeploymentListener.class)::stream, AdapterBeanRegistrarSupport.collaborators(
                          supplierContext,
                          adapterId), supplierContext.beanProvider(DummyTaskWiringSource.class)::stream, supplierContext
                              .beanProvider(DummyBpmsInitiatedStartSource.class)::stream, supplierContext
                                  .beanProvider(DummyProcessVersionSource.class)::stream, supplierContext
                                      .beanProvider(DummyHealthSource.class)::stream)));

        });

  }

}
