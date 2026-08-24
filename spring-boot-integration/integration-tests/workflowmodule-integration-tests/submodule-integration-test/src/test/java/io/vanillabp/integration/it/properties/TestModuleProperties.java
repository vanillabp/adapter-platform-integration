package io.vanillabp.integration.it.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Bound to the keys of the workflow module <i>test-module</i> to prove that its
 * files reach {@code @ConfigurationProperties} and not only {@code ${...}}
 * placeholders. Version 1 kept them out of the environment, so this is what
 * Version 2 added and what the profile support must not take away again.
 */
@ConfigurationProperties(prefix = "test-module")
@Getter
@Setter
public class TestModuleProperties {

  private int test;

  private int test2;

  private int testUnmodified;

  private String externalSystemUrl;

  private String overriddenFromClasspath;

  private String overriddenFromExternalFile;

  private String profileOnly;

}
