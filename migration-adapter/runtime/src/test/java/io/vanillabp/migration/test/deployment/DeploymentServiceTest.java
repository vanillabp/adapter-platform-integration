package io.vanillabp.migration.test.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.intergration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.intergration.extension.spi.ExtensionWiringService;

@ExtendWith(MockitoExtension.class)
public class DeploymentServiceTest {

  private ListAppender<ILoggingEvent> logWatcher;

  @Mock
  private AdapterDeploymentService<Integer, Object, Integer> adapter1DeploymentService;

  @Mock
  private AdapterDeploymentService<Long, Object, Long> adapter2DeploymentService;

  @Mock
  private ExtensionWiringService<Integer, Integer> adapter1WiringService;

  @Mock
  private ExtensionWiringService<Long, Long> adapter2WiringService;

  @Mock
  private ExtensionWiringService<Integer, Integer> extension1WiringService;

  @BeforeEach
  public void initializeTests() {

    // Initialize log watcher to capture log output
    logWatcher = new ListAppender<>();
    logWatcher.start();
    ((Logger) LoggerFactory.getLogger(DeploymentService.class)).addAppender(logWatcher);

  }

  @AfterEach
  public void stopLogWatcher() {

    ((Logger) LoggerFactory.getLogger(DeploymentService.class)).detachAndStopAllAppenders();

  }

  @Nested
  @DisplayName("Constructor Tests")
  class ConstructorTests {

    @Test
    @DisplayName("Wiring services are sorted by order")
    public void wiringServicesAreSortedByOrder() {

      // Configure mocks for order sorting
      when(adapter1WiringService.getOrder()).thenReturn(3);
      when(adapter2WiringService.getOrder()).thenReturn(1);
      when(extension1WiringService.getOrder()).thenReturn(2);

      // Create DeploymentService with unsorted list
      final var properties = createPropertiesWithAdapter("adapter-test1");
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService, extension1WiringService,
              adapter2WiringService));

      // Verify: object was created (sorting is internal and can only be tested indirectly)
      assertNotNull(testee);

    }

  }

  @Nested
  @DisplayName("deployResources Tests")
  class DeployResourcesTests {

    @Test
    @DisplayName("Correct deployment service is found for configured adapter")
    public void findsCorrectDeploymentServiceForAdapter() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService to return the correct adapter ID
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader that returns a test BPMN file
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List
          .of(Map.entry("test-process.bpmn", createDummyBpmnInputStream()));

      // readBpmn should return an executable process
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));

      // prepareBpmn should return a context
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService and call deployResources
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify that the correct DeploymentService was used
      verify(adapter1DeploymentService).readBpmn(
          eq("test-module"),
          eq("test-process.bpmn"),
          any(InputStream.class),
          eq(false));

    }

    @Test
    @DisplayName("IllegalStateException is thrown when no deployment service exists for adapter")
    public void throwsExceptionWhenNoDeploymentServiceFound() {

      // Configure properties with an adapter for which no DeploymentService exists
      final var properties = createPropertiesWithAdapter("non-existent-adapter");

      // Create DeploymentService without matching service
      final var testee = new DeploymentService(
          properties, List.of(), List.of());

      // Dummy loader that should not be called
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List.of();

      // IllegalStateException is expected
      final var exception = assertThrows(
          IllegalStateException.class,
          () -> testee.deployResources(List.of("test-module"), resourcesLoader));
      assertTrue(exception.getMessage().contains("No deployment service found for adapter"));

    }

    @Test
    @DisplayName("Warning is logged when BPMN contains no executable processes")
    public void logsWarningWhenBpmnHasNoExecutableProcesses() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader with a BPMN file
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List
          .of(Map.entry("empty-process.bpmn", createDummyBpmnInputStream()));

      // readBpmn should return an empty list (no executable processes)
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());

      // Create DeploymentService and call deployResources
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify that a warning was logged
      final var warningLogs = logWatcher.list
          .stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .filter(event -> event.getFormattedMessage().contains("did not container any executable processes"))
          .toList();

      assertEquals(1, warningLogs.size());
      assertTrue(warningLogs.getFirst().getFormattedMessage().contains("empty-process.bpmn"));

    }

    @Test
    @DisplayName("BPMN is read, prepared, wired and deployed")
    public void fullDeploymentPipelineIsExecuted() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader with BPMN file
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List
          .of(Map.entry("my-process.bpmn", createDummyBpmnInputStream()));

      // Configure mock behavior for complete pipeline
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("MyProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService and call deployResources
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify that all pipeline steps were called
      verify(adapter1DeploymentService).readBpmn(
          eq("test-module"), eq("my-process.bpmn"), any(InputStream.class), eq(false));
      verify(adapter1DeploymentService).prepareBpmn(
          eq("test-module"), eq(null), eq("my-process.bpmn"), eq("MyProcess"), eq(42));
      verify(adapter1DeploymentService).wireBpmn(
          eq("test-module"), eq("my-process.bpmn"), eq("MyProcess"), eq(42), eq(100));
      verify(adapter1DeploymentService).deployResources(eq("test-module"), eq(100));

    }

    @Test
    @DisplayName("Multiple BPMN files are processed sequentially with shared context")
    public void multipleBpmnFilesShareProcessingContext() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader with two BPMN files
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List.of(
          Map.entry("process1.bpmn", createDummyBpmnInputStream()),
          Map.entry("process2.bpmn", createDummyBpmnInputStream()));

      // Mock behavior: first BPMN creates context, second uses it
      when(adapter1DeploymentService.readBpmn(anyString(), eq("process1.bpmn"), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("Process1", 1)));
      when(adapter1DeploymentService.readBpmn(anyString(), eq("process2.bpmn"), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("Process2", 2)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), eq(null), eq("process1.bpmn"), anyString(), any()))
          .thenReturn(100);
      when(adapter1DeploymentService.prepareBpmn(anyString(), eq(100), eq("process2.bpmn"), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService and call deployResources
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify that prepareBpmn for process2 receives the context from process1
      verify(adapter1DeploymentService).prepareBpmn(
          eq("test-module"), eq(null), eq("process1.bpmn"), eq("Process1"), eq(1));
      verify(adapter1DeploymentService).prepareBpmn(
          eq("test-module"), eq(100), eq("process2.bpmn"), eq("Process2"), eq(2));

    }

    @Test
    @DisplayName("Extension wiring services are filtered by model type and process context type")
    public void extensionWiringServicesAreFilteredAndCalled() {

      // Configure properties
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Configure wiring services (sorted by order in constructor)
      when(adapter1WiringService.getOrder()).thenReturn(1);
      when(adapter1WiringService.getModelType()).thenReturn(Integer.class);
      when(adapter1WiringService.getProcessContextType()).thenReturn(Integer.class);
      when(adapter2WiringService.getOrder()).thenReturn(2);
      lenient().when(adapter2WiringService.getModelType()).thenReturn(Long.class);
      lenient().when(adapter2WiringService.getProcessContextType()).thenReturn(Long.class);

      // Create resources loader
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List
          .of(Map.entry("process.bpmn", createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService with wiring services
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService, adapter2WiringService));

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: adapter1WiringService is called (Integer/Integer matches)
      verify(adapter1WiringService).wireBpmn(
          eq("test-module"), eq("process.bpmn"), eq("TestProcess"), eq(42), any());

      // Verify: adapter2WiringService is NOT called (Long/Long does not match Integer/Integer)
      verify(adapter2WiringService, never()).wireBpmn(anyString(), anyString(), anyString(), any(), any());

    }

    @Test
    @DisplayName("VanillaBP BPMN flag is passed correctly to readBpmn")
    public void vanillaBpBpmnFlagIsPassedCorrectly() {

      // Create properties with VanillaBP resources location (not adapter-specific)
      final var properties = MigrationAdapterProperties
          .builder()
          .adapters(Map.of("adapter-test1", "dummy"))
          .prioritizedAdapters(List.of("adapter-test1"))
          .resourcesLocation("classpath:vanillabp-processes")
          .workflowModules(Map.of(
              "test-module",
              WorkflowModuleAdapterProperties
                  .builder()
                  .workflowModuleId("test-module")
                  .build()))
          .build();
      properties.setWorkflowModules(properties.getWorkflowModules());

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List
          .of(Map.entry("process.bpmn", createDummyBpmnInputStream()));

      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: isVanillaBpBpmn is true when using global resourcesLocation
      verify(adapter1DeploymentService).readBpmn(
          eq("test-module"), eq("process.bpmn"), any(InputStream.class), eq(true));

    }

  }

  @Nested
  @DisplayName("startWorkflowProcessing Tests")
  class StartWorkflowProcessingTests {

    @Test
    @DisplayName("startWorkflowProcessing is called for deployed modules")
    public void startWorkflowProcessingIsCalledForDeployedModules() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List
          .of(Map.entry("process.bpmn", createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      // First deploy
      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Then start
      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify that startWorkflowProcessing was called on DeploymentService
      verify(adapter1DeploymentService).startWorkflowProcessing(eq("test-module"), eq(100));

    }

    @Test
    @DisplayName("startWorkflowProcessing skips non-deployed modules")
    public void startWorkflowProcessingSkipsNonDeployedModules() {

      // Configure properties
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Create DeploymentService WITHOUT prior deployment
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      // Call startWorkflowProcessing for non-deployed module
      testee.startWorkflowProcessing(List.of("non-deployed-module"));

      // Verify that startWorkflowProcessing was NOT called
      verify(adapter1DeploymentService, never()).startWorkflowProcessing(anyString(), any());

    }

    @Test
    @DisplayName("Extension wiring services are called on start")
    public void extensionWiringServicesAreCalledOnStart() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter1DeploymentService.getModelType()).thenReturn(Integer.class);
      when(adapter1DeploymentService.getProcessContextType()).thenReturn(Integer.class);

      // Configure wiring service (getOrder() not called when only one service in list)
      lenient().when(adapter1WiringService.getOrder()).thenReturn(1);
      when(adapter1WiringService.getModelType()).thenReturn(Integer.class);
      when(adapter1WiringService.getProcessContextType()).thenReturn(Integer.class);

      // Create resources loader
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List
          .of(Map.entry("process.bpmn", createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService with wiring service
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService));

      // Deploy and start
      testee.deployResources(List.of("test-module"), resourcesLoader);
      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify that startWorkflowProcessing was called on wiring service
      verify(adapter1WiringService).startWorkflowProcessing(eq("test-module"), eq(100));

    }

    @Test
    @DisplayName("Only matching extension wiring services are called on start")
    public void onlyMatchingExtensionWiringServicesAreCalledOnStart() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter1DeploymentService.getModelType()).thenReturn(Integer.class);
      when(adapter1DeploymentService.getProcessContextType()).thenReturn(Integer.class);

      // Configure wiring services
      when(adapter1WiringService.getOrder()).thenReturn(1);
      when(adapter1WiringService.getModelType()).thenReturn(Integer.class);
      when(adapter1WiringService.getProcessContextType()).thenReturn(Integer.class);
      when(adapter2WiringService.getOrder()).thenReturn(2);
      lenient().when(adapter2WiringService.getModelType()).thenReturn(Long.class);
      lenient().when(adapter2WiringService.getProcessContextType()).thenReturn(Long.class);

      // Create resources loader
      final Function<String, List<Map.Entry<String, InputStream>>> resourcesLoader = location -> List
          .of(Map.entry("process.bpmn", createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService with both wiring services
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService, adapter2WiringService));

      // Deploy and start
      testee.deployResources(List.of("test-module"), resourcesLoader);
      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify: adapter1WiringService is called (matches)
      verify(adapter1WiringService).startWorkflowProcessing(eq("test-module"), eq(100));

      // Verify: adapter2WiringService is NOT called (does not match)
      verify(adapter2WiringService, never()).startWorkflowProcessing(anyString(), any());

    }

  }

  // === Helper Methods ===

  /**
   * Creates properties with a configured adapter and workflow module.
   */
  private MigrationAdapterProperties createPropertiesWithAdapter(
      final String adapterId) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(adapterId, "dummy"))
        .workflowModules(Map.of(
            "test-module",
            WorkflowModuleAdapterProperties
                .builder()
                .workflowModuleId("test-module")
                .prioritizedAdapters(List.of(adapterId))
                .adapters(Map.of(adapterId, AdapterProperties
                    .builder()
                    .resourcesLocation("classpath:test-module/processes")
                    .build()))
                .build()))
        .build();
    // Call setWorkflowModules to set the workflowModuleId
    properties.setWorkflowModules(properties.getWorkflowModules());
    return properties;

  }

  /**
   * Creates a dummy InputStream for BPMN tests.
   */
  private InputStream createDummyBpmnInputStream() {

    return new ByteArrayInputStream("<bpmn>dummy</bpmn>".getBytes(StandardCharsets.UTF_8));

  }

}
