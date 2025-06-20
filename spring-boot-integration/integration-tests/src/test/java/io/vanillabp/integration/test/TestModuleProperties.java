package io.vanillabp.integration.test;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.modules.WorkflowModuleIdAwareProperties;

@ConfigurationProperties(prefix = TestModuleProperties.WORKFLOW_MODULE_ID)
public class TestModuleProperties implements WorkflowModuleIdAwareProperties {

  public static final String WORKFLOW_MODULE_ID = "test-module";

  @Override
  public String getWorkflowModuleId() {
    return WORKFLOW_MODULE_ID;
  }

}
