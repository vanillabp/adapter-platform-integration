package io.vanillabp.integration.test;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.test.multibpmn.Aggregate1;
import io.vanillabp.integration.test.multibpmn.Aggregate2;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.spi.process.ProcessService;

/**
 * Test application which the spring-boot-maven-plugin repackages as an executable (fat)
 * JAR. The workflow modules 'test-module' and 'multi-bpmn-module' are dependencies. So
 * inside the fat JAR they are nested JARs ({@code jar:nested:} protocol). At startup,
 * the application prints the workflow module of each {@link ProcessService} to stdout,
 * and the integration test checks that output. After the start, the application shuts
 * down gracefully. Closing the context stops workflow processing through the stop path
 * of SmartLifecycle.
 */
@SpringBootApplication
// This application stores none of its three aggregates. It starts, prints the workflow
// module of each process service and shuts down. VanillaBP asks an application who owns
// its aggregates, and the double of the sample module answers for all three
@org.springframework.context.annotation.Import(io.vanillabp.integration.test.sample.NoPersistenceForTheSampleAggregate.class)
public class FatJarTestApplication {

  public static void main(
      final String[] args) {

    // closing the context on exit triggers the graceful-shutdown path
    try (final var context = SpringApplication.run(FatJarTestApplication.class, args)) {
      context.getBean(FatJarTestApplication.class);
    }

  }

  /**
   * Reports the workflow module of each process service to stdout.
   */
  @Bean
  public ApplicationRunner workflowModuleReporter(
      final ProcessService<Aggregate> sampleProcessService,
      final ProcessService<Aggregate1> multiBpmn1ProcessService,
      final ProcessService<Aggregate2> multiBpmn2ProcessService) {

    return args -> {
      System.out.println("FATJAR-TEST sample: "
          + sampleProcessService.getWorkflowModuleId());
      System.out.println("FATJAR-TEST multibpmn1: "
          + multiBpmn1ProcessService.getWorkflowModuleId());
      System.out.println("FATJAR-TEST multibpmn2: "
          + multiBpmn2ProcessService.getWorkflowModuleId());
    };

  }

}
