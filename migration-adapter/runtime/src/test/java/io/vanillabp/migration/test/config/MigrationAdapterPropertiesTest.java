package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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

    assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(adaptersLoaded, List.of()),
        """
            The following adapters were configured in properties section 'vanillabp.adapters' but there is no adapter in classpath matching the given type:
               adapter-unknown of type adapter-unknown
            Available adapter types in classpath: [adapter1, adapter2]"""
    );

    assertEquals(0, logWatcher.list.size());

  }

  @Test
  public void testWorkflowModulesConfiguredButNotInClasspath() {

    final var properties = new MigrationAdapterProperties();

    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setWorkflowModules(Map.of("test-module", testModule));

    properties.validateProperties(adaptersLoaded, List.of());

    assertEquals(1, logWatcher.list.size());
    assertEquals("""
        Found properties for workflow modules
          vanillabp.workflow-modules.test-module
        which were not found in the class-path!""", logWatcher.list.getLast().getFormattedMessage());

  }

  @Test
  public void testOnlyOneAdapterInClasspath() {

    final var properties = new MigrationAdapterProperties();

    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setWorkflowModules(Map.of("test-module", testModule));

    properties.validateProperties(List.of("adapter2"), List.of());

    assertEquals(2, logWatcher.list.size());
    assertEquals("""
        Found only one VanillaBP adapter 'adapter2' in classpath. Please ensure the properties
          vanillabp.workflow-modules.test-module.adapters.adapter2.resources-location
        are specific to this adapter in order to avoid future-problems once you wish to migrate to another adapter.""",
        logWatcher.list.getLast().getFormattedMessage());

  }

  @Test
  public void testAdaptersReferencedButNotConfigured() {

    final var properties = new MigrationAdapterProperties();

    properties.setAdapters(Map.of("adapter-unknown", "adapter-unknown"));

    properties.setAdapters(Map.of("adapter-test", "adapter2"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .prioritizedAdapters(List.of("adapter-test", "unknown-adapter"))
            .build()))
        .build()));

    assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("adapter2"), List.of()),
        """
            There are VanillaBP adapters referenced not found in any property section 'vanillabp.adapters.*':
              vanillabp.workflow-modules.test-module.workflows.testProcess.prioritized-adapters => unknown-adapter"""
    );

  }

}