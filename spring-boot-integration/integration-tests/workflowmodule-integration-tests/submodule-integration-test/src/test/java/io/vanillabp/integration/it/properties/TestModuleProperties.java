package io.vanillabp.integration.it.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to the keys of the workflow module <i>test-module</i> to prove that its
 * files reach {@code @ConfigurationProperties} and not only {@code ${...}}
 * placeholders. Version 1 kept them out of the environment, so this is what
 * Version 2 added and what the profile support must not take away again.
 */
@ConfigurationProperties(prefix = "test-module")
public class TestModuleProperties {

  private int test;

  private int test2;

  private int testUnmodified;

  private String externalSystemUrl;

  private String overriddenFromClasspath;

  private String overriddenFromExternalFile;

  private String profileOnly;

  public int getTest() {

    return test;

  }

  public void setTest(
      final int test) {

    this.test = test;

  }

  public int getTest2() {

    return test2;

  }

  public void setTest2(
      final int test2) {

    this.test2 = test2;

  }

  public int getTestUnmodified() {

    return testUnmodified;

  }

  public void setTestUnmodified(
      final int testUnmodified) {

    this.testUnmodified = testUnmodified;

  }

  public String getExternalSystemUrl() {

    return externalSystemUrl;

  }

  public void setExternalSystemUrl(
      final String externalSystemUrl) {

    this.externalSystemUrl = externalSystemUrl;

  }

  public String getOverriddenFromClasspath() {

    return overriddenFromClasspath;

  }

  public void setOverriddenFromClasspath(
      final String overriddenFromClasspath) {

    this.overriddenFromClasspath = overriddenFromClasspath;

  }

  public String getOverriddenFromExternalFile() {

    return overriddenFromExternalFile;

  }

  public void setOverriddenFromExternalFile(
      final String overriddenFromExternalFile) {

    this.overriddenFromExternalFile = overriddenFromExternalFile;

  }

  public String getProfileOnly() {

    return profileOnly;

  }

  public void setProfileOnly(
      final String profileOnly) {

    this.profileOnly = profileOnly;

  }

}
