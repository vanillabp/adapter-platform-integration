package io.vanillabp.integration.test.electioncost;

import java.util.Collection;
import java.util.List;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The application of this scenario: one workflow aggregate, one BPMN process, and a BPMS
 * double whose read model a test can put behind.
 */
@SpringBootApplication
public class TestApplication {

  /**
   * The double this scenario elects from.
   *
   * @return The read model a test steers
   */
  @Bean
  public ALaggingReadModel theReadModelOfTheBpms() {

    return new ALaggingReadModel();

  }

  /**
   * Stands in for what an adapter reads out of the model of this scenario: which BPMN
   * process the file declares, and that it has no task needing a handler.
   *
   * @return What this scenario's BPMN file holds
   */
  @Bean
  public DummyTaskWiringSource costProcessModel() {

    return new DummyTaskWiringSource() {

      @Override
      public List<String> executableProcessesOf(
          final String adapterId,
          final String workflowModuleId,
          final String filename) {

        return "cost-process.bpmn".equals(filename)
            ? List.of("CostProcess")
            : List.of();

      }

      @Override
      public Collection<BpmnTaskSpec> tasksOf(
          final String adapterId,
          final String workflowModuleId,
          final String bpmnProcessId) {

        return List.of();

      }

    };

  }

}
