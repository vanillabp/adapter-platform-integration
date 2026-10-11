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
 * A file of a workflow module ships defaults. The application wins over it, no matter
 * which of its own files has the value.
 *
 * <p>Three sources of the same key meet here: <i>test-module.yaml</i> inside the jar of
 * the workflow module, <i>application.yaml</i> on the classpath of the application, and
 * <i>config/application.yaml</i> next to the runner. While the tests run, the runner's
 * directory is the directory of this Maven module.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(classes = {
    TestApplication.class, WorkflowModuleConfigurationIsADefaultTest.BindWorkflowModuleProperties.class
})
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

    // the improvement of Version 2: these values are real environment properties,
    // not only visible to ${...}
    assertEquals(8, environment.getProperty("test-module.test-unmodified", int.class));
    assertEquals(8, testModuleProperties.getTestUnmodified());
    assertEquals(11, testModuleProperties.getTest());
    assertEquals(4710, testModuleProperties.getTest2());

  }

}
