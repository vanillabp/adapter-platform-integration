package io.vanillabp.integration.test.processservice;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * THE migration scenario. Instances were started while adapter 'old-bpms' had the first
 * priority. Then the configuration was flipped: 'new-bpms' moved up, 'old-bpms' moved down.
 * Operations on OLD instances still go to 'old-bpms', because the election asks each adapter
 * and finds the workflow there. NEW workflows start in 'new-bpms'. The second operation on the
 * same workflow does not ask the adapters again, because the election cache remembers the
 * answer.
 * <p>
 * The test also proves the SPI for replacing the cache: a {@link WorkflowAdapterCache} bean of
 * the application replaces the in-memory default.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MigrationElectionTest {

  // Each test uses its own task IDs. Background threads may write lines of other tests into
  // the captured output, and the counts must not include them.
  private static final String OLD_INSTANCE_TASK_PHASE_ONE = "Dummy-Adapter[old-bpms]: Completing task 'task-mig' (phase one)";

  private static final String NEW_START_PHASE_ONE = "Dummy-Adapter[new-bpms]: Starting workflow (phase one)";

  private static final String OLD_START_PHASE_ONE = "Dummy-Adapter[old-bpms]: Starting workflow (phase one)";

  /**
   * Persistence double for the sample aggregate.
   */
  @Configuration
  static class AggregatePersistenceConfiguration {

    @Bean
    AggregatePersistenceAware<Aggregate> testAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<Aggregate> getAggregateClass() {
          return Aggregate.class;
        }

        @Override
        public Aggregate save(
            final Aggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final Aggregate aggregate) {
          return "4711";
        }

      };

    }

  }

  /**
   * What the BPMS double knows: the workflow of aggregate '4711' lives in 'old-bpms'. It was
   * started before the priorities were flipped. 'new-bpms' does not know it.
   */
  @Configuration
  static class OldInstanceAwarenessConfiguration {

    @Bean
    DummyTaskAwarenessSource oldInstanceAwareness() {

      return (
          adapterId,
          workflowAggregateId,
          taskId) -> "old-bpms".equals(adapterId) && "4711".equals(String.valueOf(workflowAggregateId))
              ? WorkflowAwareness.ACTIVE
              : WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

  }

  /**
   * An election cache which the application provides, as a cache shared in a cluster would.
   * It records every access. So the test can check that it replaced the in-memory default.
   */
  static class RecordingWorkflowAdapterCache implements WorkflowAdapterCache {

    final Map<String, String> entries = new ConcurrentHashMap<>();

    final List<String> puts = new CopyOnWriteArrayList<>();

    final List<String> gets = new CopyOnWriteArrayList<>();

    private String key(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {
      return "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, workflowAggregateId);
    }

    @Override
    public Optional<String> get(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {
      final var key = key(workflowModuleId, bpmnProcessId, workflowAggregateId);
      gets.add(key);
      return Optional.ofNullable(entries.get(key));
    }

    @Override
    public void put(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String adapterId) {
      final var key = key(workflowModuleId, bpmnProcessId, workflowAggregateId);
      puts.add(key
          + "->"
          + adapterId);
      entries.put(key, adapterId);
    }

    @Override
    public void invalidate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {
      entries.remove(key(workflowModuleId, bpmnProcessId, workflowAggregateId));
    }

  }

  @Configuration
  static class ApplicationProvidedCacheConfiguration {

    @Bean
    RecordingWorkflowAdapterCache applicationWorkflowAdapterCache() {

      return new RecordingWorkflowAdapterCache();

    }

  }

  /**
   * The flipped configuration: 'new-bpms' has the first priority NOW. The instances of '4711'
   * were started while 'old-bpms' was first.
   */
  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - new-bpms
          - old-bpms
        adapters:
          new-bpms:
            type: dummy
          old-bpms:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              new-bpms:
                resources-location: classpath*:test-module/processes/dummy
              old-bpms:
                resources-location: classpath*:test-module/processes/dummy
      test-module:
        nothing: there
      """;

  private SpringBootTestApplication testApplication() throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  @SuppressWarnings("unchecked")
  private static ProcessService<Aggregate> processServiceOf(
      final org.springframework.context.ApplicationContext context) {

    return (ProcessService<Aggregate>) context
        .getBeanProvider(ResolvableType
            .forClassWithGenerics(ProcessService.class, Aggregate.class))
        .getObject();

  }

  private static void inFakeTransaction(
      final Runnable operation) {

    // The dummy adapter completes a task completely in phase one, without a two-phase commit.
    // The process-service bean only needs an ACTIVE transaction.
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      operation.run();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

  }

  @Test
  public void oldInstancesRouteToOldBpmsNewStartsToNewBpms(
      final CapturedOutput output) throws IOException {

    try (var testApp = testApplication(); var context = testApp.applicationBuilder(
        DummyAdapterConfiguration.class,
        DummyAdapterProcessServiceConfiguration.class,
        WorkflowModuleAutoConfiguration.class,
        SpringBootMigrationAdapterAutoConfiguration.class,
        TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
        TestTransactionRunnerConfiguration.class,
        SampleWorkflowService.class,
        WorkflowModuleConfiguration.class,
        AggregatePersistenceConfiguration.class,
        OldInstanceAwarenessConfiguration.class)
        .run()) {

      final var processService = processServiceOf(context);

      // An operation on the OLD instance. The election asks the adapters and sends it to
      // 'old-bpms', although 'new-bpms' has the first priority now.
      final var oldAggregate = new Aggregate();
      inFakeTransaction(() -> processService.completeTask(oldAggregate, "task-mig"));

      // Everything read below is the output of this test alone. The sentences which must be
      // absent name an adapter id which the neighbour test configures as well. A sentence
      // which rules out the neighbour would have to spell out the whole line. The counts read
      // the same view, for the same reason.
      final var afterOldOperation = output.getAllOfThisTest();
      Assertions.assertTrue(
          afterOldOperation.contains(OLD_INSTANCE_TASK_PHASE_ONE),
          "expected the task of the old instance to complete on 'old-bpms' but got: "
              + afterOldOperation);
      Assertions.assertFalse(
          afterOldOperation.contains("Dummy-Adapter[new-bpms]: Completing task"),
          "the task must never be completed on 'new-bpms' but got: "
              + afterOldOperation);

      // The election asked 'new-bpms' first, because it has the first priority. Count these
      // questions to prove that the SECOND operation takes the answer from the cache.
      final var newBpmsProbes = countOccurrences(
          output.getAllOfThisTest(), "Dummy-Adapter[new-bpms]: Checking awareness of task 'task-mig'");

      inFakeTransaction(() -> processService.completeTask(oldAggregate, "task-mig"));

      Assertions.assertEquals(
          newBpmsProbes,
          countOccurrences(output.getAllOfThisTest(), "Dummy-Adapter[new-bpms]: Checking awareness of task 'task-mig'"),
          "the second operation must skip the walk (cache hit) - 'new-bpms' probed again");
      Assertions.assertEquals(
          2,
          countOccurrences(output.getAllOfThisTest(), OLD_INSTANCE_TASK_PHASE_ONE),
          () -> "the second operation must still execute on 'old-bpms' but got: "
              + output
                  .getAllOfThisTest()
                  .lines()
                  .filter(line -> line.contains("Dummy-Adapter"))
                  .collect(java.util.stream.Collectors.joining("\n")));

      // NEW workflows start in the adapter which has the first priority NOW
      processService.startWorkflow(new Aggregate());

      final var afterStart = output.getAllOfThisTest();
      Assertions.assertTrue(
          afterStart.contains(NEW_START_PHASE_ONE),
          "expected new workflows to start in 'new-bpms' but got: "
              + afterStart);
      Assertions.assertFalse(
          afterStart.contains(OLD_START_PHASE_ONE),
          "new workflows must never start in the demoted 'old-bpms' but got: "
              + afterStart);

    }

  }

  @Test
  public void applicationProvidedCacheReplacesTheDefault(
      final CapturedOutput output) throws IOException {

    try (var testApp = testApplication(); var context = testApp.applicationBuilder(
        DummyAdapterConfiguration.class,
        DummyAdapterProcessServiceConfiguration.class,
        WorkflowModuleAutoConfiguration.class,
        SpringBootMigrationAdapterAutoConfiguration.class,
        TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
        TestTransactionRunnerConfiguration.class,
        SampleWorkflowService.class,
        WorkflowModuleConfiguration.class,
        AggregatePersistenceConfiguration.class,
        OldInstanceAwarenessConfiguration.class,
        ApplicationProvidedCacheConfiguration.class)
        .run()) {

      final var processService = processServiceOf(context);
      final var cache = context.getBean(RecordingWorkflowAdapterCache.class);

      inFakeTransaction(() -> processService.completeTask(new Aggregate(), "task-cache"));

      // the election read the application's cache AND wrote to it
      Assertions.assertFalse(cache.gets.isEmpty(), "the election must consult the application's cache");
      Assertions.assertEquals(1, cache.puts.size(), "one successful election, one put");
      Assertions.assertTrue(
          cache.puts.getFirst().endsWith("|4711->old-bpms"),
          "the successful election must be stored in the application's cache but got: "
              + cache.puts);

    }

  }

  private static int countOccurrences(
      final String text,
      final String needle) {

    var count = 0;
    var index = text.indexOf(needle);
    while (index >= 0) {
      ++count;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;

  }

}
