package io.vanillabp.integration.it.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 101: a file of a workflow module ships defaults. The application wins
 * over it, whichever of its own files carries the value.
 *
 * <p>Three sources of the same key meet here: <i>test-module.yaml</i> inside the
 * workflow module's jar, <i>application.yaml</i> on the application's classpath
 * and <i>config/application.yaml</i> next to the runner, which is this Maven
 * module's directory while its tests run.
 */
@SpringBootTest(classes = {
    TestApplication.class, WorkflowModuleConfigurationIsADefaultTest.BindWorkflowModuleProperties.class
})
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleConfigurationIsADefaultTest {

  @Configuration
  @EnableConfigurationProperties(TestModuleProperties.class)
  static class BindWorkflowModuleProperties {
  }

  @Autowired
  Environment environment;

  @Autowired
  TestModuleProperties testModuleProperties;

  @Value("${test-module.external-system-url}")
  String externalSystemUrlFromPlaceholder;

  @Test
  public void testPlaceholderOfAModuleFileResolvesAgainstTheApplication() {

    // test-module.yaml writes ${urls.external-system}, application.yaml sets it
    assertEquals("https://es.com", externalSystemUrlFromPlaceholder);
    assertEquals("https://es.com", environment.getProperty("test-module.external-system-url"));
    assertEquals("https://es.com", testModuleProperties.getExternalSystemUrl());

  }

  @Test
  public void testApplicationClasspathBeatsTheModulesFile() {

    assertEquals(
        "from-the-applications-classpath",
        environment.getProperty("test-module.overridden-from-classpath"));
    assertEquals(
        "from-the-applications-classpath",
        testModuleProperties.getOverriddenFromClasspath());

  }

  @Test
  public void testFileNextToTheRunnerBeatsTheModulesFile() {

    assertEquals(
        "from-the-file-next-to-the-runner",
        environment.getProperty("test-module.overridden-from-external-file"));
    assertEquals(
        "from-the-file-next-to-the-runner",
        testModuleProperties.getOverriddenFromExternalFile());

  }

  @Test
  public void testAValueOnlyTheModuleShipsIsStillVisible() {

    // the improvement of Version 2: these values are proper environment
    // properties, not just something ${...} can see
    assertEquals(8, environment.getProperty("test-module.test-unmodified", int.class));
    assertEquals(8, testModuleProperties.getTestUnmodified());
    assertEquals(11, testModuleProperties.getTest());
    assertEquals(4710, testModuleProperties.getTest2());

  }

}
