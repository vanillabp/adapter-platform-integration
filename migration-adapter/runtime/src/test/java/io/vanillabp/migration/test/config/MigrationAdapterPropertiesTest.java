package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.config.AdapterConfiguration;
import io.vanillabp.integration.config.MigrationAdapterProperties;
import io.vanillabp.integration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.config.WorkflowModuleAdapterProperties;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MigrationAdapterPropertiesTest {

  private ListAppender<ILoggingEvent> logWatcher;

  private final List<String> adaptersLoaded = List.of("adapter1", "adapter2");

  private final WorkflowModuleAdapterProperties testModule = WorkflowModuleAdapterProperties
      .builder()
      .workflowModuleId("test-module")
      .adapters(Map.of("adapter-test", AdapterConfiguration
          .builder()
          .resourcesLocation("classpath:test-modules/processes/test")
          .build()))
      .workflows(Map.of("testProcess", WorkflowAdapterProperties
          .builder()
          .bpmnProcessId("testProcess")
          .build()))
      .build();

  @BeforeEach
  public void initLogWatcher() {

    logWatcher = new ListAppender<>();
    logWatcher.start();
    ((Logger) LoggerFactory.getLogger(MigrationAdapterProperties.class)).addAppender(logWatcher);

  }

  @AfterEach
  public void stopLogWatcher() {

    ((Logger) LoggerFactory.getLogger(MigrationAdapterProperties.class)).detachAndStopAllAppenders();

  }

  @Test
  public void testAdapterTypesNotInClasspath() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-unknown", "adapter-unknown"));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(adaptersLoaded, List.of("test-module"))
    );
    assertEquals(
        """
            The following adapters were configured in properties section 'vanillabp.adapters' but there is no adapter in classpath matching the given type:
               adapter-unknown of type adapter-unknown
            Available adapter types in classpath: [adapter1, adapter2]""",
        exception.getMessage());

  }

  @Test
  public void testNoResourceLocationGiven() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setWorkflowModules(Map.of("test-module", testModule));
    properties.setPrioritizedAdapters(List.of("adapter-test"));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(adaptersLoaded, List.of("fake-module"))
    );
    assertEquals(
        """
            Property 'vanillabp.workflow-modules.fake-module.adapters.adapter-test.resources-location' not set!
            It has to point to a location specific to the adapter in order to avoid future problems once you wish to migrate to another adapter.
            Sample: 'classpath*:/workflow-resources/adapter-test'""",
        exception.getMessage());

  }

  @Test
  public void testWorkflowModulesConfiguredButNotInClasspath() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setWorkflowModules(Map.of("fake-module", testModule));
    properties.setPrioritizedAdapters(List.of("adapter-test"));

    assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(adaptersLoaded, List.of("test-module"))
    );

    assertTrue(logWatcher.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .anyMatch(msg -> msg.equals("""
            Found properties for workflow modules
              vanillabp.workflow-modules.fake-module
            which were not found in the class-path!""")));

  }

  @Test
  public void testMissingPrioritizedAdapters() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setWorkflowModules(Map.of("test-module", testModule));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("adapter2"), List.of("test-module"))
    );
    assertEquals(
        """
            You need to define at least one property of
              vanillabp.prioritized-adapters
              vanillabp.workflow-modules.test-module.prioritized-adapters
            """,
        exception.getMessage());

  }

  @Test
  public void testPrioritizedAdapters() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(
        "adapter-test1", "adapter1",
        "adapter-test2", "adapter2"));
    properties.setPrioritizedAdapters(List.of("adapter-test2", "adapter-test1"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .adapters(Map.of("adapter-test1", AdapterConfiguration
            .builder()
            .resourcesLocation("classpath*:processes/test1")
            .build(),
            "adapter-test2", AdapterConfiguration
                .builder()
                .resourcesLocation("classpath*:processes/test2")
                .build()))
        .prioritizedAdapters(List.of("adapter-test1", "adapter-test2"))
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .prioritizedAdapters(List.of("adapter-test1"))
            .build()))
        .build()));

    final var global = properties.getPrioritizedAdaptersFor(null, null);
    assertEquals(List.of("adapter-test2", "adapter-test1"), global);

    final var module = properties.getPrioritizedAdaptersFor("test-module", null);
    assertEquals(List.of("adapter-test1", "adapter-test2"), module);

    final var workflow = properties.getPrioritizedAdaptersFor("test-module", "testProcess");
    assertEquals(List.of("adapter-test1"), workflow);

  }

  @Test
  public void testOnlyOneAdapterInClasspath() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setWorkflowModules(Map.of("test-module", testModule));
    properties.setPrioritizedAdapters(List.of("adapter-test"));

    properties.validateProperties(List.of("adapter2"), List.of("test-module"));

    assertTrue(logWatcher.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .anyMatch(msg -> msg.equals(
            """
                Found only one VanillaBP adapter 'adapter-test' configured. Please ensure the properties
                  vanillabp.workflow-modules.test-module.adapters.adapter-test.resources-location
                are specific to this adapter in order to avoid future-problems once you wish to migrate to another adapter.""")));

  }

  @Test
  public void testAdaptersReferencedButNotConfigured() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setPrioritizedAdapters(List.of("unknown-adapter1"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .prioritizedAdapters(List.of("unknown-adapter2"))
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .prioritizedAdapters(List.of("adapter-test", "unknown-adapter3"))
            .build()))
        .build()));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("adapter2"), List.of("test-module"))
    );
    assertEquals(
        """
            There are VanillaBP adapters referenced not found in any property section 'vanillabp.adapters.*':
              vanillabp.workflow-modules.test-module.workflows.testProcess.prioritized-adapters => unknown-adapter3
              vanillabp.workflow-modules.test-module.prioritized-adapters => unknown-adapter2
              vanillabp.prioritized-adapters => unknown-adapter1
            """,
        exception.getMessage());
  }

  @Test
  public void testValidatePropertiesForHavingWrongPrioritizedAdapters() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setPrioritizedAdapters(List.of("unknown-adapter1"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .prioritizedAdapters(List.of("unknown-adapter2"))
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .prioritizedAdapters(List.of("adapter-test", "unknown-adapter3"))
            .build()))
        .build()));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validatePropertiesFor(List.of("adapter-test"), "test-module", "testProcess"));

    assertEquals(
        """
            Property 'prioritized-adapters' of workflow-module 'test-module' and bpmn-process-id 'testProcess' contains adapters not configured in 'vanillabp.adapters.*':
              unknown-adapter3
            Available adapters are: 'adapter-test'!""",
        exception.getMessage());

  }

  @Test
  public void testValidatePropertiesForHavingWrongNoPrioritizedAdapters() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .build()))
        .build()));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validatePropertiesFor(List.of("adapter-test"), "test-module", "testProcess"));

    assertEquals(
        """
            More than one VanillaBP adapter was configured, but no default adapter is configured at
              vanillabp.workflow-modules.test-module.workflows.testProcess.prioritized-adapters or
              vanillabp.workflow-modules.test-module.prioritized-adapters or
              vanillabp.prioritized-adapters
            Available adapters are 'adapter-test'.""",
        exception.getMessage());

  }

}