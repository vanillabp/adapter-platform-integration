package io.vanillabp.integration.test.processservice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.samples.twoaggregates.TwoAggregates;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Two classes of ONE workflow aggregate declare the same process and the same called step.
 * That stays allowed: the classes are merged, and the application starts.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AProcessOfOneAggregateDeclaredTwiceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(TwoAggregates.LoanAggregate.class)
          .addClass(TwoAggregates.LoanAggregatePersistence.class)
          .addClass(TwoAggregates.AlsoCallsTheProcess.class)
          .addClass(TwoAggregates.SecondHalfOfTheProcess.class)
          .addClass(io.vanillabp.integration.test.adapter.DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentService.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer.class)
          .addClass(io.vanillabp.integration.test.adapter.TestMigratableProcessService.class)
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());

  @Inject
  ProcessService<TwoAggregates.LoanAggregate> processService;

  @Test
  @DisplayName("Classes of one aggregate sharing a process and a called step still start")
  public void classesOfOneAggregateShareAProcess() {

    final var registrations = java.util.List.of(((ProcessServiceBaseCdiBean<?>) processService)
        .getWorkflowTaskRegistrations()
        .split(";"));

    Assertions.assertTrue(
        registrations.contains("%s|%s|%s".formatted(
            TwoAggregates.MODULE,
            TwoAggregates.AlsoCallsTheProcess.class.getName(),
            TwoAggregates.SHARED_PROCESS)),
        registrations::toString);
    Assertions.assertTrue(
        registrations.contains("%s|%s|%s".formatted(
            TwoAggregates.MODULE,
            TwoAggregates.SecondHalfOfTheProcess.class.getName(),
            TwoAggregates.SHARED_PROCESS)),
        registrations::toString);

  }

}
