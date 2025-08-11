package io.vanillabp.integration.test;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = TestModuleProperties.WORKFLOW_MODULE_ID)
public class TestModuleProperties {

  public static final String WORKFLOW_MODULE_ID = "test-module";

}
