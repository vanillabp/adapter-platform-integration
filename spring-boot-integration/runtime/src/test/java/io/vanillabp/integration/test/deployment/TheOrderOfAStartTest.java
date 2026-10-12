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
 * What a platform integration owes the core when it starts an application. It deploys the
 * resources. It runs the checks which need a deployed adapter. Then it closes the start.
 * <p>
 * Nothing else would notice a missing third step. Take an integration which never calls
 * {@link DeploymentService#endOfStartup()}. It boots an application which looks healthy.
 * There is no block of what the start found. The reasons not to start stay collected in
 * memory, and they do not end the boot. This test catches that for Spring Boot.
 * <code>TheOrderOfAStartTest</code> of the Quarkus integration catches it there.
 * <p>
 * The moment of the third step differs per platform, so this test fixes only the order.
 * Spring Boot closes its start together with the deployment. So a context which is only
 * refreshed gets the block too. Spring Boot starts the workflow processing later, on
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
    aStart.verify(electionCheck).sayWhereStartMessagesAreNotCheckedAfterDeployment();
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

    // the Spring Boot order. It is the reason why the two platforms do not write the same
    // block. Here, what the start of the workflow processing notices arrives after the block
    // was written. So it is logged where it was found
    final InOrder aStart = Mockito.inOrder(deploymentService);
    aStart.verify(deploymentService).endOfStartup();
    aStart.verify(deploymentService).startWorkflowProcessing(eq(List.of("test-module")));

  }

}
