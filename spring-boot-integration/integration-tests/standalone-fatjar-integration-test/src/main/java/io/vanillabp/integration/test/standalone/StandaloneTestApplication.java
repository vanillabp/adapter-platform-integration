package io.vanillabp.integration.test.standalone;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.spi.process.ProcessService;

/**
 * An application which IS its workflow module: the file <code>META-INF/workflow-module</code>
 * and the BPMN files below <code>processes/</code> are part of this artifact. The
 * repackaged JAR keeps the classes below <code>BOOT-INF/classes/</code> and the marker file
 * at the top of the JAR, which is the case the integration test starts. The application
 * reports where VanillaBP looked for the BPMN files and stops again.
 */
@SpringBootApplication
@org.springframework.context.annotation.Import(NoPersistenceForTheStandaloneAggregate.class)
public class StandaloneTestApplication {

  /**
   * Starts the application and closes it once it has started.
   *
   * @param args The command line arguments
   */
  public static void main(
      final String[] args) {

    try (final var context = SpringApplication.run(StandaloneTestApplication.class, args)) {
      context.getBean(StandaloneTestApplication.class);
    }

  }

  /**
   * Reports the workflow module and the locations searched for its BPMN files to stdout.
   *
   * @param processService The process service of the one workflow
   * @param properties The configuration VanillaBP derived
   * @return The runner which prints the report
   */
  @Bean
  public ApplicationRunner whereTheBpmnFilesWereSearched(
      final ProcessService<StandaloneAggregate> processService,
      final MigrationAdapterProperties properties) {

    return args -> {
      System.out.println("STANDALONE-TEST module: "
          + processService.getWorkflowModuleId());
      properties
          .getAdapterResourcesLocationsFor(processService.getWorkflowModuleId(), "dummy")
          .forEach(location -> System.out.println("STANDALONE-TEST searched: "
              + location.location()));
    };

  }

}
