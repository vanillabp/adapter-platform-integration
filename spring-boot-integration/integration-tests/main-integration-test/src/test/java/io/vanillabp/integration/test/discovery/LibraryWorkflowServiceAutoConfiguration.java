package io.vanillabp.integration.test.discovery;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The auto-configuration of a library bringing a workflow service. Auto-configurations
 * are read after every configuration class of the application, so this is the bean
 * definition arriving last - the one a discovery running too early would miss.
 */
@AutoConfiguration
public class LibraryWorkflowServiceAutoConfiguration {

  @Bean
  public LibraryWorkflowService libraryWorkflowService() {

    return new LibraryWorkflowService();

  }

}
