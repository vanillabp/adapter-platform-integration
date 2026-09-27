package io.vanillabp.integration.test.deployment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.deployment.SpringBootDeploymentService;
import io.vanillabp.integration.processservice.ProcessServiceSpringBean;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a platform integration owes the core when it starts an application: deploy the
 * resources, run the checks which need a deployed adapter, then close the start.
 * <p>
 * The third step is the one nothing else would notice. An integration which never calls
 * {@link DeploymentService#endOfStartup()} boots an application which looks healthy: no
 * block of what the start found, and the reasons not to start sit collected in memory
 * instead of ending the boot. This test is what says so for Spring Boot, and
 * <code>TheOrderOfAStartTest</code> of the Quarkus integration says it there.
 * <p>
 * Where the third step falls differs per platform, and only the order is pinned here.
 * Spring Boot closes its start with the deployment, so that a context which is only
 * refreshed gets the block too, and starts the workflow processing later, on
 * <code>ApplicationReadyEvent</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheOrderOfAStartTest {

  @Test
  @DisplayName("A start deploys, checks the elections and then ends the start")
  @SuppressWarnings("unchecked")
  public void aStartDeploysChecksAndEnds() {

    final var deploymentService = mock(DeploymentService.class);
    final var workflowModules = new WorkflowModules(List.of(
        WorkflowModule.builder().id("test-module").sourceUri("file:///test").build()));
    final var electionCheck = (MigrationProcessService<Object>) mock(MigrationProcessService.class);
    final var processServiceBean = (ProcessServiceSpringBean<Object>) mock(ProcessServiceSpringBean.class);
    when(processServiceBean.getProcessServicesOfDeclaredIds()).thenReturn(List.of(electionCheck));
    final var processServices = (ObjectProvider<ProcessService<?>>) mock(ObjectProvider.class);
    when(processServices.stream()).thenAnswer(invocation -> Stream.of(processServiceBean));

    final var testee = new SpringBootDeploymentService(
        deploymentService, workflowModules, processServices);

    testee.start();

    final InOrder aStart = Mockito.inOrder(deploymentService, electionCheck);
    aStart.verify(deploymentService).deployResources(eq(List.of("test-module")), any());
    aStart.verify(electionCheck).validateElectionCapabilityAfterDeployment();
    aStart.verify(deploymentService).endOfStartup();

  }

  @Test
  @DisplayName("The workflow processing starts after the start was ended")
  @SuppressWarnings("unchecked")
  public void theWorkflowProcessingStartsAfterwards() {

    final var deploymentService = mock(DeploymentService.class);
    final var workflowModules = new WorkflowModules(List.of(
        WorkflowModule.builder().id("test-module").sourceUri("file:///test").build()));
    final var processServices = (ObjectProvider<ProcessService<?>>) mock(ObjectProvider.class);
    when(processServices.stream()).thenAnswer(invocation -> Stream.empty());

    final var testee = new SpringBootDeploymentService(
        deploymentService, workflowModules, processServices);

    testee.start();
    testee.startProcessingOfWorkflows();

    // the Spring Boot order, and the reason the two platforms do not write the same
    // block: what the start of the workflow processing notices arrives here after the
    // block was written, so it is logged where it was found
    final InOrder aStart = Mockito.inOrder(deploymentService);
    aStart.verify(deploymentService).endOfStartup();
    aStart.verify(deploymentService).startWorkflowProcessing(eq(List.of("test-module")));

  }

}
