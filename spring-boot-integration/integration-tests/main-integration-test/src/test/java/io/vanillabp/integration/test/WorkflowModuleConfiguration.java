package io.vanillabp.integration.test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;


@Configuration
@EnableConfigurationProperties(TestModuleProperties.class)
public class WorkflowModuleConfiguration {

}
