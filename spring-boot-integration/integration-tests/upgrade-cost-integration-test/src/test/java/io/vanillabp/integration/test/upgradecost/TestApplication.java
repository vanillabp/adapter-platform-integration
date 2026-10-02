package io.vanillabp.integration.test.upgradecost;

import java.util.Collection;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The application of this scenario: one workflow aggregate, one BPMN process with one
 * service task, a BPMS double which requires the two phases of a remote BPMS, and a data
 * source which counts.
 */
@SpringBootApplication
public class TestApplication {

  /**
   * Where the calls of phase two arrive.
   *
   * @return The watcher a test reads the dispatch threads from
   */
  @Bean
  public WhereTheDispatchRan whereTheDispatchRan() {

    return new WhereTheDispatchRan();

  }

  /**
   * Puts the meter between the application and its database. A post processor rather than a
   * data source of its own: what is measured has to be the pooled data source Spring Boot
   * builds for the application, not a second one beside it.
   *
   * @return The post processor wrapping the one data source of this application
   */
  @Bean
  public static BeanPostProcessor countWhatTheDatabaseIsAskedFor() {

    return new BeanPostProcessor() {

      @Override
      public Object postProcessAfterInitialization(
          final Object bean,
          final String beanName) {

        return bean instanceof DataSource dataSource
            ? new WhatTheDatabaseWasAskedFor(dataSource)
            : bean;

      }

    };

  }

  /**
   * Stands in for what an adapter reads out of the model of this scenario: the BPMN process
   * the file declares and its one service task.
   *
   * @return What this scenario's BPMN file holds
   */
  @Bean
  public DummyTaskWiringSource upgradeCostProcessModel() {

    return new DummyTaskWiringSource() {

      @Override
      public List<String> executableProcessesOf(
          final String adapterId,
          final String workflowModuleId,
          final String filename) {

        return "upgrade-cost-process.bpmn".equals(filename)
            ? List.of(CostWorkflowService.PROCESS)
            : List.of();

      }

      @Override
      public Collection<BpmnTaskSpec> tasksOf(
          final String adapterId,
          final String workflowModuleId,
          final String bpmnProcessId) {

        return CostWorkflowService.PROCESS.equals(bpmnProcessId)
            ? List.of(new BpmnTaskSpec("Activity_1c9pa8d", CostWorkflowService.TASK))
            : List.of();

      }

    };

  }

}
