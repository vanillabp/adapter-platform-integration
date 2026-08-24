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
 * Test application repackaged as an executable (fat) JAR by the
 * spring-boot-maven-plugin. The workflow modules 'test-module' and
 * 'multi-bpmn-module' are pulled in as dependencies, so inside the fat JAR they are
 * nested JARs ({@code jar:nested:} protocol). On startup the workflow module of
 * each {@link ProcessService} is reported to stdout to be asserted by the
 * integration test. Once started, the application shuts down gracefully (closing
 * the context stops workflow processing via the SmartLifecycle stop path).
 */
@SpringBootApplication
// three aggregates none of which this application persists: it starts, reports the
// workflow module of each process service and shuts down. VanillaBP asks an application to
// say who owns its aggregates, and the sample module's double answers for all of them
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
