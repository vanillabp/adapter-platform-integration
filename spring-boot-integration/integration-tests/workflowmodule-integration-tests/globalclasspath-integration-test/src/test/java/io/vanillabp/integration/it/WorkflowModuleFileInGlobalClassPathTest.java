package io.vanillabp.integration.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import io.vanillabp.integration.test.TestApplication;

@SpringBootTest(classes = {
    TestApplication.class
})
//@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleFileInGlobalClassPathTest {

  @Test
  public void testStartingApplicationCompletedWithoutError() {
    // nothing to test
  }

}