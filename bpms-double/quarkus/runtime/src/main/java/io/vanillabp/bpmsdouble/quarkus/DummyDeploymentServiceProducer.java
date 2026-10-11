package io.vanillabp.bpmsdouble.quarkus;

import java.util.List;

import io.vanillabp.bpmsdouble.DummyBpmsInitiatedStartSource;
import io.vanillabp.bpmsdouble.DummyDeploymentListener;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyHealthSource;
import io.vanillabp.bpmsdouble.DummyProcessVersionSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Provides the double's {@link DummyDeploymentService} instances. It is the reference
 * for the bean rule every VanillaBP adapter follows on Quarkus, one instance per adapter
 * id. The shape is the same as for the process services (see
 * {@link DummyProcessServiceProducer}): ONE bean of type
 * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code>, with one
 * instance PER configured adapter id of its type.
 * <p>
 * The rules (part of the platform contract):
 * <ul>
 *   <li>The element type of the List is the SPI interface with BOTH type parameters
 *       written as {@code Object}. This holds whatever the adapter's real model and
 *       context classes are; cast unchecked if needed. CDI does not match generic types
 *       with different type arguments reliably in every mode. So the platform looks the
 *       beans up by the exact type. The pipeline matches models through
 *       {@code getModelType()} and {@code getProcessContextType()}, never through the
 *       generics.</li>
 *   <li>The producer method is {@code @Singleton}. A deployment service usually has no
 *       constructor without arguments, so CDI cannot build a client proxy for it. A
 *       normal-scoped <i>element</i> bean of the implementation class would make the
 *       deployment fail.</li>
 *   <li>The set of adapter ids ALWAYS comes from the platform's core properties
 *       ({@code adapterIdsOfType()}, see {@link DummyProcessServiceProducer}).</li>
 * </ul>
 */
@ApplicationScoped
public class DummyDeploymentServiceProducer {

  /**
   * CDI builds this producer once per application. A test never creates it and never
   * calls the method below. It asks the container for the deployment services, or lets
   * the platform's deployment pipeline do it.
   */
  public DummyDeploymentServiceProducer() {
  }

  /**
   * Builds one {@link DummyDeploymentService} per configured adapter id of the dummy
   * type, as the one List bean described above.
   * <p>
   * Every collaborator is passed to the constructor, and none is set later. See
   * decision 28 in the repository's DECISIONS.md.
   *
   * @param properties The platform's core properties, the only place the adapter ids
   *          come from
   * @param deploymentListeners The hooks a test declared to watch the pipeline
   * @param workflowTaskRegistry The wiring half and the runtime half of the task SPI
   * @param scoping The core's one place which builds the identifiers a BPMS sees
   * @param workflowAggregateSync What says which values of an aggregate leave for the
   *          BPMS
   * @param preCommitRegistrar What runs a check right before the caller's transaction
   *          commits
   * @param workflowEndedInvoker The core bean reporting a workflow which ended, absent
   *          where the application asks for none
   * @param bpmsInitiatedStartInvoker The core bean reporting a workflow the BPMS started
   *          by itself, absent where the application asks for none
   * @param taskWiringSource The hooks standing in for the BPMN model
   * @param bpmsInitiatedStartSource The hooks standing in for the model's start events
   * @param processVersionSource The hooks standing in for the versions the BPMS deployed
   * @param healthSource The hooks standing in for what the BPMS answers about itself
   * @return One deployment service per configured adapter id of the dummy type
   */
  @Produces
  @Singleton
  public List<AdapterDeploymentService<Object, Object>> dummyAdapterDeploymentServices(
      final MigrationAdapterProperties properties,
      @Any final Instance<DummyDeploymentListener> deploymentListeners,
      final io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry workflowTaskRegistry,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync workflowAggregateSync,
      final io.vanillabp.integration.adapter.spi.PreCommitRegistrar preCommitRegistrar,
      @Any final Instance<io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker> workflowEndedInvoker,
      @Any final Instance<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker,
      @Any final Instance<DummyTaskWiringSource> taskWiringSource,
      @Any final Instance<DummyBpmsInitiatedStartSource> bpmsInitiatedStartSource,
      @Any final Instance<DummyProcessVersionSource> processVersionSource,
      @Any final Instance<DummyHealthSource> healthSource) {

    return properties
        .adapterIdsOfType(DummyProcessServiceProducer.ADAPTER_TYPE)
        .stream()
        .<AdapterDeploymentService<Object, Object>>map(
            adapterId -> new DummyDeploymentService(
                adapterId, deploymentListeners::stream, io.vanillabp.integration.runtime.support.AdapterCollaboratorsSupport
                    .collaborators(
                        adapterId, workflowTaskRegistry, workflowTaskRegistry, scoping, workflowAggregateSync,
                        preCommitRegistrar, workflowEndedInvoker,
                        bpmsInitiatedStartInvoker), taskWiringSource::stream, bpmsInitiatedStartSource::stream, processVersionSource::stream, healthSource::stream))
        .toList();

  }

}
