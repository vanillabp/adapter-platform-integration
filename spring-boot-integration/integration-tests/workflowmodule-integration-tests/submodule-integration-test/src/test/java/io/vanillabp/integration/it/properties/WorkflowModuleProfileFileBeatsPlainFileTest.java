package io.vanillabp.integration.it.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The one order inside a workflow module which had to survive moving
 * its files below the application - <i>test-module-testprofile.yaml</i> beats
 * <i>test-module.yaml</i>, and both still lose against the application.
 */
@SpringBootTest(classes = {
    TestApplication.class, WorkflowModuleProfileFileBeatsPlainFileTest.BindWorkflowModuleProperties.class
})
@ActiveProfiles("testprofile")
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleProfileFileBeatsPlainFileTest {

  @Configuration
  @EnableConfigurationProperties(TestModuleProperties.class)
  static class BindWorkflowModuleProperties {
  }

  @Autowired
  Environment environment;

  @Autowired
  TestModuleProperties testModuleProperties;

  @Test
  public void testProfileFileOfTheModuleBeatsItsPlainFile() {

    // 21 from test-module-testprofile.yaml, not 11 from test-module.yaml
    assertEquals(21, testModuleProperties.getTest());
    // 4720 from test-module-testprofile.properties, not 4710 from test-module.properties
    assertEquals(4720, testModuleProperties.getTest2());
    assertEquals(
        "from-the-workflow-module-testprofile",
        environment.getProperty("test-module.profile-only"));

  }

  @Test
  public void testTheApplicationStillWinsOverTheModulesProfileFile() {

    assertEquals(
        "from-the-applications-classpath",
        environment.getProperty("test-module.overridden-from-classpath"));
    assertEquals(
        "from-the-file-next-to-the-runner",
        environment.getProperty("test-module.overridden-from-external-file"));

  }

  @Test
  public void testAValueOnlyThePlainModuleFileShipsStaysVisible() {

    assertEquals(8, testModuleProperties.getTestUnmodified());

  }

}
