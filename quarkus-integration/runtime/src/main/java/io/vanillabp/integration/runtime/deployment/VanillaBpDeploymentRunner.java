package io.vanillabp.integration.runtime.deployment;

import java.util.List;
import java.util.stream.Stream;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.spi.process.ProcessService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the deployment pipeline of the core at boot. It is the Quarkus counterpart of the
 * Spring Boot integration's <code>SpringBootDeploymentService</code>. For every workflow
 * module and every prioritized adapter, it runs the pipeline
 * <code>readBpmn &rarr; prepareBpmn &rarr; wireBpmn &rarr; deployResources &rarr;
 * startWorkflowProcessing</code>. It follows the
 * <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code> policy. It wires the
 * extensions in the order of <code>getOrder()</code>. All of this lives in the core's
 * {@link DeploymentService}.
 * <p>
 * <b>The lifecycle compared with Spring Boot</b> (see also the module README): Spring
 * deploys while the context refreshes (<code>SmartLifecycle.start()</code>). It starts the
 * workflow processing on <code>ApplicationReadyEvent</code>, that is after the web server
 * started serving. The usual hook on Quarkus is the {@link StartupEvent}. It fires before the
 * application accepts traffic. So on Quarkus, both the deployment and the start of the
 * workflow processing happen <i>before</i> serving. The end of the start is the end of this
 * one observer. The end of the start means the block of everything the start noticed, and
 * the collected refusals. Spring has to end its start earlier, see
 * <code>SpringBootDeploymentService</code>. Both platforms share one rule: the phase-two
 * outbox dispatchers start dispatching (crash recovery included) only <i>after</i> the
 * workflow processing started. Here, {@link #STARTUP_PRIORITY} and
 * {@link #OUTBOX_DISPATCHER_STARTUP_PRIORITY} make sure of that. On Spring,
 * <code>ApplicationReadyEvent</code> listeners with an <code>@Order</code> do it.
 * <p>
 * The {@link AdapterDeploymentService} beans of the adapters are collected as element beans
 * plus flattened beans of type
 * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code> (one instance per
 * configured adapter id). This is the same per-adapter-id convention as for the process
 * services. Extensions add plain {@link ExtensionWiringService} element beans.
 */
@Slf4j
@ApplicationScoped
public class VanillaBpDeploymentRunner {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   * An application never creates one. What the runner needs arrives through the injected
   * fields. And the shutdown observer must stop the same pipeline which this runner holds.
   */
  public VanillaBpDeploymentRunner() {
  }

  /**
   * Priority of the deployment runner's {@link StartupEvent} observer. It runs AFTER the
   * observers with default priority. These are the startup checks of the process services,
   * e.g. the checks for a prioritized adapter which nothing serves and for the phase-two
   * outbox. They run BEFORE anything is deployed. Spring Boot does the same: it checks at
   * <code>SmartInitializingSingleton</code> and deploys at
   * <code>SmartLifecycle.start()</code>. The runner runs BEFORE the phase-two outbox
   * dispatchers ({@link #OUTBOX_DISPATCHER_STARTUP_PRIORITY}). A phase-two operation from
   * crash recovery must never be dispatched before the deployment pipeline deployed the
   * resources and started the workflow processing.
   */
  public static final int STARTUP_PRIORITY = Interceptor.Priority.APPLICATION + 600;

  /**
   * Priority of the {@link StartupEvent} observers of the phase-two outbox dispatchers. They
   * run after the deployment runner ({@link #STARTUP_PRIORITY}).
   */
  public static final int OUTBOX_DISPATCHER_STARTUP_PRIORITY = Interceptor.Priority.APPLICATION + 700;

  @Inject
  MigrationAdapterProperties properties;

  @Inject
  BpmsResourceIndex bpmsResourceIndex;

  /**
   * The deployment services of the adapters as <i>element</i> beans (one bean per adapter).
   */
  @Inject
  @Any
  Instance<AdapterDeploymentService<?, ?>> adapterDeploymentServices;

  /**
   * A second accepted shape: beans of type
   * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code>. They are
   * flattened into the collected deployment services, one instance per configured adapter id.
   * A CDI producer cannot create N element beans for N ids configured at runtime. By
   * convention, both type parameters of the element are LITERALLY {@code Object}, whatever
   * model and context classes the adapter really uses. CDI does not match parameterized types
   * with different type arguments reliably in all modes. So the platform looks the beans up
   * with the exact type. The pipeline matches models through {@code getModelType()} and
   * {@code getProcessContextType()}, never through the generics.
   */
  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> adapterDeploymentServiceLists;

  /**
   * The wiring services of the extensions (element beans). Adapters may show up here too,
   * because {@link AdapterDeploymentService} extends {@link ExtensionWiringService}. The
   * core's {@link DeploymentService} filters them out.
   */
  @Inject
  @Any
  Instance<ExtensionWiringService<?, ?>> extensionWiringServices;

  /**
   * All generated process-service beans. They are stopped on graceful shutdown, like Spring
   * Boot does in <code>SmartLifecycle.stop()</code>.
   */
  @Inject
  @Any
  Instance<ProcessService<?>> processServices;

  /**
   * The core's wiring interface. It goes to the {@link DeploymentService} for the checks which
   * belong to a workflow module as a whole. They run once the last adapter of the module
   * finished deploying. They look at the deployed processes which no
   * <code>&#64;WorkflowService</code> class claims, and at the versions a BPMS still holds for
   * a process id which the application only declares. They also look at the
   * <code>&#64;WorkflowTask</code> methods which serve no task at all, and at the version tags
   * which the annotations name. The core runs these checks, so that no adapter can forget
   * them.
   */
  @Inject
  io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry workflowTaskWiring;

  private DeploymentService deploymentService;

  private List<String> workflowModuleIds;

  private volatile boolean running = false;

  /**
   * Builds the core's {@link DeploymentService}, deploys the BPMN resources of all workflow
   * modules found in the classpath and starts the workflow processing.
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes
      @Priority(STARTUP_PRIORITY) final StartupEvent event) {

    deployAndStart();

  }

  /**
   * Builds the core's deployment service for this start. It is a separate method, not a
   * <code>new</code> in the middle of the start. So a test can replace it and watch the order
   * in which the steps of a start call the service.
   *
   * @param deploymentServices The deployment service of every configured adapter
   * @param wiringServices The wiring service of every extension taking part
   * @return The deployment service this start runs
   */
  DeploymentService deploymentServiceOf(
      final List<AdapterDeploymentService<?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices) {

    return new DeploymentService(
        properties, deploymentServices, wiringServices, workflowTaskWiring);

  }

  synchronized void deployAndStart() {

    workflowModuleIds = bpmsResourceIndex.getWorkflowModuleIds();

    final List<AdapterDeploymentService<?, ?>> deploymentServices = Stream
        .concat(
            adapterDeploymentServices.stream(),
            adapterDeploymentServiceLists
                .stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream))
        .filter(java.util.Objects::nonNull)
        .<AdapterDeploymentService<?, ?>>map(service -> service)
        .toList();
    final List<ExtensionWiringService<?, ?>> wiringServices = extensionWiringServices
        .stream()
        .toList();

    deploymentService = deploymentServiceOf(deploymentServices, wiringServices);

    // What the BUILD found out about the configuration files of the workflow modules.
    // The recorder which reports it runs before there are beans. So it kept the
    // sentence, and this is the first moment it can go into the block of the start.
    final var configFilesReport = io.vanillabp.integration.runtime.config.WorkflowModuleConfigFilesRecorder
        .takeReport();
    if (configFilesReport != null) {
      properties
          .startupFindings()
          .warn(
              io.vanillabp.integration.spi.startup.StartupTopic.CONFIGURATION,
              null,
              configFilesReport);
    }

    log.info("Deploying BPMN resources of workflow modules: {}", workflowModuleIds);
    deploymentService.deployResources(
        workflowModuleIds,
        bpmsResourceIndex::loadResources);

    // Check the election capability of the prioritized adapters. This happens after the
    // adapters deployed, because only then does an adapter know what its BPMS can do.
    // And it happens before anything touches a workflow.
    processServices
        .stream()
        .filter(ProcessServiceBaseCdiBean.class::isInstance)
        .map(processService -> (ProcessServiceBaseCdiBean<?>) processService)
        // Per DECLARED BPMN process id. Which adapters serve a workflow can be configured
        // per workflow. So a secondary id or an id which is only declared may be the one
        // whose combination of adapters cannot find its workflows.
        .flatMap(processService -> processService.getProcessServicesOfDeclaredIds().stream())
        .forEach(processService -> {
          processService.validateElectionCapabilityAfterDeployment();
          processService.sayWhereStartMessagesAreNotCheckedAfterDeployment();
        });

    deploymentService.startWorkflowProcessing(workflowModuleIds);

    // The end of the start. On Quarkus it is right here. The deployment and the start of
    // the workflow processing happen in one observer, so nothing a start can notice comes
    // later. On Spring Boot, the two are split over the lifecycle and the ready event,
    // and the end of the start is the earlier of them.
    deploymentService.endOfStartup();

    running = true;

  }

  /**
   * Stops the workflow processing of adapters and extensions in reverse start order. Then it
   * stops all process services. {@link VanillaBpShutdownObserver} calls it on graceful
   * shutdown. It is safe to call it more than once, because later calls do nothing.
   */
  public synchronized void stop() {

    if (!running) {
      return;
    }

    log.info("Stopping workflow processing of workflow modules: {}", workflowModuleIds);
    deploymentService.stopWorkflowProcessing(workflowModuleIds);

    processServices
        .stream()
        .filter(ProcessServiceBaseCdiBean.class::isInstance)
        .map(processService -> (ProcessServiceBaseCdiBean<?>) processService)
        .forEach(ProcessServiceBaseCdiBean::stopService);

    running = false;

  }

}
