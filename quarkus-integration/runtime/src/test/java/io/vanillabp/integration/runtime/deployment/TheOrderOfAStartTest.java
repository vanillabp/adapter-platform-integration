package io.vanillabp.integration.runtime.deployment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mockito;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.runtime.test.InstanceDouble;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a platform integration owes the core when it starts an application. It deploys the
 * resources, runs the checks which need a deployed adapter, and then closes the start.
 * <p>
 * Nothing else would notice if the last step were missing. An integration which never calls
 * {@link DeploymentService#endOfStartup()} boots an application which looks healthy. There
 * is no block of what the start found. The reasons not to start are collected in memory,
 * but they do not end the boot. This test says so for Quarkus.
 * <code>TheOrderOfAStartTest</code> of the Spring Boot integration says it there.
 * <p>
 * Quarkus deploys and starts the workflow processing in one observer of the startup event.
 * So it closes the start after both, and its block holds what either of them noticed.
 * Spring Boot has to close earlier. That is why the two platforms do not write the same
 * block.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheOrderOfAStartTest {

  /**
   * A runner whose pipeline is a mock. So the test can read from it the order of the calls
   * which a start makes on the core.
   */
  private static class RunnerWithAWatchedPipeline extends VanillaBpDeploymentRunner {

    private final DeploymentService pipeline;

    RunnerWithAWatchedPipeline(
        final DeploymentService pipeline) {

      this.pipeline = pipeline;

    }

    @Override
    DeploymentService deploymentServiceOf(
        final List<AdapterDeploymentService<?, ?>> deploymentServices,
        final List<ExtensionWiringService<?, ?>> wiringServices) {

      return pipeline;

    }

  }

  @Test
  @DisplayName("A start deploys, checks the elections, starts the processing and then ends the start")
  @SuppressWarnings("unchecked")
  public void aStartDeploysChecksStartsAndEnds() {

    final var pipeline = mock(DeploymentService.class);
    final var resourceIndex = mock(BpmsResourceIndex.class);
    when(resourceIndex.getWorkflowModuleIds()).thenReturn(List.of("test-module"));
    final var electionCheck = (MigrationProcessService<Object>) mock(MigrationProcessService.class);
    final var processServiceBean = (ProcessServiceBaseCdiBean<Object>) mock(ProcessServiceBaseCdiBean.class);
    when(processServiceBean.getProcessServicesOfDeclaredIds()).thenReturn(List.of(electionCheck));

    final var testee = new RunnerWithAWatchedPipeline(pipeline);
    testee.properties = new MigrationAdapterProperties();
    testee.bpmsResourceIndex = resourceIndex;
    testee.workflowTaskWiring = mock(WorkflowTaskRegistry.class);
    testee.adapterDeploymentServices = InstanceDouble.of(List.<AdapterDeploymentService<?, ?>>of());
    testee.adapterDeploymentServiceLists = InstanceDouble
        .of(List.<List<AdapterDeploymentService<Object, Object>>>of());
    testee.extensionWiringServices = InstanceDouble.of(List.<ExtensionWiringService<?, ?>>of());
    testee.processServices = InstanceDouble.of(List.<ProcessService<?>>of(processServiceBean));

    testee.deployAndStart();

    final InOrder aStart = Mockito.inOrder(pipeline, electionCheck);
    aStart.verify(pipeline).deployResources(eq(List.of("test-module")), any());
    aStart.verify(electionCheck).validateElectionCapabilityAfterDeployment();
    aStart.verify(electionCheck).sayWhereStartMessagesAreNotCheckedAfterDeployment();
    aStart.verify(pipeline).startWorkflowProcessing(eq(List.of("test-module")));
    aStart.verify(pipeline).endOfStartup();

  }

}
