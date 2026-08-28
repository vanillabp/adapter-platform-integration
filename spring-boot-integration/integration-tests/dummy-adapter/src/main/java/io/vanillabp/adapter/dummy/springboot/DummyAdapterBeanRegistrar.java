package io.vanillabp.adapter.dummy.springboot;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import io.vanillabp.adapter.dummy.springboot.deployment.DeploymentService;
import io.vanillabp.adapter.dummy.springboot.deployment.DummyBpmsInitiatedStartSource;
import io.vanillabp.adapter.dummy.springboot.deployment.DummyTaskWiringSource;
import io.vanillabp.adapter.dummy.springboot.processservice.DummyAdapterPhaseTwoListener;
import io.vanillabp.adapter.dummy.springboot.processservice.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.adapter.dummy.springboot.processservice.MigratableProcessService;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;

/**
 * Registers the dummy adapter's per-adapter-id beans - the reference implementation
 * of the per-id bean convention every VanillaBP adapter follows on Spring Boot: for
 * EACH configured adapter id of the adapter's type (multiple ids of one BPMS type =
 * the migration scenario) one {@code MigratableProcessService} <i>element</i> bean
 * and one {@code AdapterDeploymentService} <i>element</i> bean are registered -
 * never beans of type {@code List<...>}: the platform collects element beans via
 * {@code ObjectProvider.stream()}.
 * <p>
 * The id set comes from the runtime configuration, so the beans are registered
 * programmatically ({@link BeanRegistrar} +
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}); the adapter id is
 * a CONSTRUCTOR parameter of each instance. The bean suppliers are lazy: other beans
 * are resolved through the {@code SupplierContext} at bean-creation time.
 */
public class DummyAdapterBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    final var deliversTasksAtLeastOnce = Boolean.TRUE.equals(
        environment.getProperty(
            DummyAdapterProcessServiceConfiguration.PROPERTY_AT_LEAST_ONCE_DELIVERY, Boolean.class, Boolean.FALSE));

    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(
        environment,
        DummyAdapterConfiguration.ADAPTER_TYPE,
        adapterId -> {

          registry.registerBean(
              "DummyAdapter_ProcessService_%s".formatted(adapterId),
              MigratableProcessService.class,
              spec -> spec.supplier(supplierContext -> new MigratableProcessService<>(
                  adapterId, deliversTasksAtLeastOnce, supplierContext
                      .beanProvider(DummyAdapterPhaseTwoListener.class), supplierContext
                          .beanProvider(
                              io.vanillabp.adapter.dummy.springboot.processservice.DummyTaskAwarenessSource.class), supplierContext
                                  .beanProvider(
                                      io.vanillabp.adapter.dummy.springboot.processservice.DummyViewerSource.class))));

          registry.registerBean(
              "DummyAdapter_DeploymentService_%s".formatted(adapterId),
              DeploymentService.class,
              spec -> spec.supplier(supplierContext -> new DeploymentService(
                  adapterId, supplierContext.bean(WorkflowTaskRegistry.class), supplierContext
                      .beanProvider(DummyTaskWiringSource.class), supplierContext
                          .bean(WorkflowTaskRegistry.class), supplierContext
                              .beanProvider(DummyBpmsInitiatedStartSource.class), supplierContext
                                  .bean(WorkflowTaskRegistry.class), supplierContext
                                      .beanProvider(
                                          io.vanillabp.adapter.dummy.springboot.deployment.DummyProcessVersionSource.class), supplierContext
                                              .beanProvider(
                                                  io.vanillabp.adapter.dummy.springboot.deployment.DummyHealthSource.class))));

        });

  }

}
