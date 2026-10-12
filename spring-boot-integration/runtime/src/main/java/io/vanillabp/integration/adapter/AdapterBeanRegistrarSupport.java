package io.vanillabp.integration.adapter;

import java.util.function.Consumer;

import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;

/**
 * Helps BPMS adapters on Spring Boot to register their beans, one set per adapter id.
 * For each configured adapter id of the adapter's type, an adapter registers ONE
 * {@code MigratableProcessService} element bean and ONE
 * {@code AdapterDeploymentService} element bean. Several ids of the same BPMS type are
 * the migration case. An adapter never registers a bean of type {@code List<...>},
 * because the platform collects element beans with {@code ObjectProvider.stream()}.
 * <p>
 * The set of ids comes from the runtime configuration. So the beans have to be
 * registered in code, by a {@code BeanRegistrar}. Configuration-properties beans are
 * not bound yet at registration time. So this helper binds the core
 * <code>vanillabp.*</code> tree directly from the {@link Environment}. The config files
 * of workflow modules are ordinary property sources, added by an
 * {@code EnvironmentPostProcessor}, so they are visible here. Usage in an adapter's
 * {@code BeanRegistrar}:
 *
 * <pre>
 * &#64;Override
 * public void register(final BeanRegistry registry, final Environment environment) {
 *   AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(environment, ADAPTER_TYPE,
 *       adapterId -&gt; registry.registerBean("MyAdapter_ProcessService_" + adapterId,
 *           MigratableProcessService.class,
 *           spec -&gt; spec.supplier(supplierContext -&gt; ...)));
 * }
 * </pre>
 *
 * The set of adapter ids ALWAYS comes from the platform's core properties, through this
 * helper. An adapter may also bind its own overlay maps of the <code>vanillabp.*</code>
 * tree. It only looks up ids it already knows in them. It must never iterate them to
 * find ids, because environment-variable overrides can create phantom map entries in
 * overlays.
 */
public final class AdapterBeanRegistrarSupport {

  private AdapterBeanRegistrarSupport() {
  }

  /**
   * Calls the given consumer for each adapter id configured in
   * <code>vanillabp.adapters.&lt;id&gt;.*</code> whose type equals the given adapter
   * type. The type counts after defaults are applied. The order is stable (sorted).
   * <p>
   * {@link MigrationAdapterProperties#adapterIdsOfType(String)} decides WHICH ids those
   * are. The rule lives in the core because Quarkus has to answer the same question. An
   * extension which bridges to a BPMS also asks it about the same configuration. What is
   * left here is binding the tree from the environment. Only Spring Boot can do that
   * part.
   *
   * @param environment The Spring environment to bind the core properties from
   * @param adapterType The adapter's type
   * @param adapterIdConsumer Invoked once per configured adapter id of the type
   */
  public static void forEachConfiguredAdapterId(
      final Environment environment,
      final String adapterType,
      final Consumer<String> adapterIdConsumer) {

    Binder
        .get(environment)
        .bind(MigrationAdapterProperties.PREFIX, Bindable.of(VanillaBpConfigurationProperties.class))
        .orElseGet(VanillaBpConfigurationProperties::new)
        .adapterIdsOfType(adapterType)
        .forEach(adapterIdConsumer);

  }

  /**
   * Collects what this platform hands to an adapter. So a registrar asks for the set
   * and does not have to remember the single beans. An application may lack two of the
   * collaborators. These are looked up as providers and stay absent if there is no bean.
   * {@link AdapterCollaborators} reports that and names the adapter id.
   *
   * @param supplierContext The context of the bean being registered
   * @param adapterId The adapter id the beans are registered for
   * @return The collaborators, complete
   * @see AdapterCollaborators
   */
  public static AdapterCollaborators collaborators(
      final BeanRegistry.SupplierContext supplierContext,
      final String adapterId) {

    return AdapterCollaborators
        .forAdapter(adapterId)
        .workflowTaskWiring(supplierContext.bean(WorkflowTaskWiring.class))
        .workflowTaskInvoker(supplierContext.bean(WorkflowTaskInvoker.class))
        .scoping(supplierContext.bean(NameClashAvoidanceSupport.class))
        .workflowAggregateSync(supplierContext.bean(WorkflowAggregateSync.class))
        .preCommitRegistrar(supplierContext.bean(PreCommitRegistrar.class))
        .workflowEndedInvoker(supplierContext.beanProvider(WorkflowEndedInvoker.class).getIfAvailable())
        .bpmsInitiatedStartInvoker(
            supplierContext.beanProvider(BpmsInitiatedStartInvoker.class).getIfAvailable())
        .build();

  }

}
