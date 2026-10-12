package io.vanillabp.integration.test.discovery;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The auto-configuration of a library which brings a workflow service. Spring reads
 * auto-configurations after every configuration class of the application. So this bean
 * definition arrives last. A discovery which runs too early would miss it.
 */
@AutoConfiguration
public class LibraryWorkflowServiceAutoConfiguration {

  @Bean
  public LibraryWorkflowService libraryWorkflowService() {

    return new LibraryWorkflowService();

  }

}
