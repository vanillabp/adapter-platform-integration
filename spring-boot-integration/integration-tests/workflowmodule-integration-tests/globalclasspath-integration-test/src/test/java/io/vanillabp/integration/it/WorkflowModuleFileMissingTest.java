package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.commons.util.ExceptionUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.core.io.DefaultResourceLoader;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleFileMissingTest {

  @Test
  void shouldFailWhenWorkflowModuleFileIsMissing() {

    final var exception = assertThrows(Exception.class, () -> {
      final var app = new SpringApplicationBuilder(TestApplication.class)
          .resourceLoader(new DefaultResourceLoader(
              new FilteredClassLoader(name -> name.contains("META-INF/workflow-module"))
          ));
      app.run();
    });
    final var rootCause = ExceptionUtils
        .findNestedThrowables(exception)
        .getLast();
    assertEquals(
        """
            There is no workflow module descriptor file META-INF/workflow-module in the application's module nor, if in a separate module, in the modules of these workflow service classes:
              - %s
            """
            .formatted(SampleWorkflowService.class.getName()),
        rootCause.getMessage());

  }

}