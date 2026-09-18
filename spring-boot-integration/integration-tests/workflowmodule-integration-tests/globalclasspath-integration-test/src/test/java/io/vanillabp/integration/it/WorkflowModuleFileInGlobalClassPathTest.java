package io.vanillabp.integration.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(classes = {
    TestApplication.class
})
public class WorkflowModuleFileInGlobalClassPathTest {

  @Test
  public void testStartingApplicationCompletedWithoutError() {
    // nothing to test
  }

}