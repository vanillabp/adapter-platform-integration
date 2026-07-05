package io.vanillabp.integration.test.workflowmodule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

public class WorkflowModulesTest {

  @Test
  public void testEmptyWorkflowModuleDescriptorFileFails() throws IOException {

    final var workflowModuleDescriptor = mock(Resource.class);
    when(workflowModuleDescriptor.getContentAsString(StandardCharsets.UTF_8))
        .thenReturn("   ");
    when(workflowModuleDescriptor.getURI()).thenReturn(URI.create("file:///XXX"));
    final var resolver = mock(ResourcePatternResolver.class);
    when(resolver.getResources("classpath*:META-INF/workflow-module"))
        .thenReturn(new Resource[]{
            workflowModuleDescriptor
        });

    final var exception = assertThrows(IllegalStateException.class,
        () -> WorkflowModuleAutoConfiguration.vanillaBpWorkflowModules(resolver));

    final var rootCause = org.junit.platform.commons.util.ExceptionUtils
        .findNestedThrowables(exception)
        .getLast();
    assertEquals("Empty workflow module descriptor 'file:///XXX'", rootCause.getMessage());

  }

  @Test
  public void testMultipleGlobalWorkflowModuleDescriptorFilesFail() {

    final var workflowModules = List.of(
        WorkflowModule.builder().id("1").sourceUri("file:///1").build(),
        WorkflowModule.builder().id("2").sourceUri("file:///2").build());

    final var exception = assertThrows(IllegalStateException.class,
        () -> WorkflowModuleAutoConfiguration.registerProcessServices(workflowModules, List.of()));

    final var rootCause = org.junit.platform.commons.util.ExceptionUtils
        .findNestedThrowables(exception)
        .getLast();
    assertEquals(
        """
            Multiple workflow module descriptor files META-INF/workflow-module were found in modules which do not contain any service class annotated with @io.vanillabp.spi.service.WorkflowService:
              - file:///1
              - file:///2
            """,
        rootCause.getMessage());

  }

}
