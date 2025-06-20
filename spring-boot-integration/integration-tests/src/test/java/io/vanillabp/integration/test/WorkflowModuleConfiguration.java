package io.vanillabp.integration.test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.modules.WorkflowModuleProperties;

@Configuration
@EnableConfigurationProperties(TestModuleProperties.class)
public class WorkflowModuleConfiguration {

  @Bean
  public static WorkflowModuleProperties testWorkflowModuleProperties() {

    return new WorkflowModuleProperties(TestModuleProperties.class, TestModuleProperties.WORKFLOW_MODULE_ID);

  }

}
