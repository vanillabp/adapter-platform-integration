package io.vanillabp.integration.it.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Validate properties read from application and workflow module properties
 * with no additional profiles.
 *
 * <p>Note: In Spring Boot, {@code application.properties} has higher priority
 * than {@code application.yaml}. For workflow module files, YAML has higher
 * priority than {@code .properties} (matching the Quarkus behavior).
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
public class ApplicationPropertiesAndYamlTest {

  @Value("${test-module.test:-1}")
  int testModuleProperty;

  @Value("${test-module.test2:-1}")
  int testModuleProperty2;

  @Value("${test-module.test-unmodified:-1}")
  int testModuleUnmodifiedProperty;

  @Value("${multi-bpmn-module.test:-1}")
  int multiBpmnModuleProperty;

  @Value("${multi-bpmn-module.test2:-1}")
  int multiBpmnModuleProperty2;

  @Value("${multi-bpmn-module.test-unmodified:-1}")
  int multiBpmnModuleUnmodifiedProperty;

  @Value("${no-module.test:-1}")
  int noModuleProperty;

  @Value("${no-module.test2:-1}")
  int noModuleProperty2;

  @Value("${no-module.test-unmodified:-1}")
  int noModuleUnmodifiedProperty;

  @Test
  public void testYamlProperties() {

    // loaded from test-module.yaml (yaml > properties for workflow module files)
    assertEquals(11, testModuleProperty);
    // loaded from multi-bpmn-module.yaml
    assertEquals(101, multiBpmnModuleProperty);
    // loaded from application.properties (properties > yaml in Spring Boot)
    assertEquals(47, noModuleProperty);

  }

  @Test
  public void testPropertiesFiles() {

    // loaded from test-module.properties (only in .properties)
    assertEquals(4710, testModuleProperty2);
    // loaded from multi-bpmn-module.properties (only in .properties)
    assertEquals(47100, multiBpmnModuleProperty2);
    // loaded from application.properties (properties > yaml in Spring Boot)
    assertEquals(4747, noModuleProperty2);

  }

  @Test
  public void testUnmodifiedProperties() {

    // loaded from test-module.yaml (not overridden, only in yaml)
    assertEquals(8, testModuleUnmodifiedProperty);
    // loaded from multi-bpmn-module.yaml (not overridden, only in yaml)
    assertEquals(9, multiBpmnModuleUnmodifiedProperty);
    // loaded from application.yaml (not overridden, only in yaml)
    assertEquals(7, noModuleUnmodifiedProperty);

  }

}
