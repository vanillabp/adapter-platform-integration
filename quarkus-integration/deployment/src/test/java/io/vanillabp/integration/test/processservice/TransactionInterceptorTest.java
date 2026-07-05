package io.vanillabp.integration.test.processservice;

import java.util.List;
import java.util.logging.LogRecord;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.processservice.TransactionInterceptor;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Tests that the {@link TransactionInterceptor} fires for methods annotated by
 * {@link WorkflowTask}. Since the annotation is repeatable, a method carrying two or
 * more <code>&#64;WorkflowTask</code> annotations only carries the container annotation
 * <code>&#64;WorkflowTasks</code> in the Jandex index. This test proves that the
 * interceptor fires for such methods, too.
 */
public class TransactionInterceptorTest {

  @ApplicationScoped
  public static class TaskService {

    @WorkflowTask
    public void singleWorkflowTask() {
    }

    @WorkflowTask(taskDefinition = "taskA")
    @WorkflowTask(taskDefinition = "taskB")
    public void repeatedWorkflowTask() {
    }

    public void noWorkflowTask() {
    }

  }

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(DummyAdapters.class)                          // necessary due to anonymous class in DummyAdapters
          .addClass(TaskService.class)                            // service having methods annotated by @WorkflowTask
          .addAsResource("application.yaml")                   // load sample application properties
          // define workflow module at global classpath
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())   // add mocked adapter
      // the interceptor logs at level INFO but tests are run using 'quarkus.log.level=ERROR'
      // (see 'Logging during tests' in the module's README.md), so the interceptor's log
      // category needs to be enabled explicitly
      .overrideConfigKey("quarkus.log.category.\""
          + TransactionInterceptor.class.getName()
          + "\".level", "INFO")
      // capture the interceptor's log records to assert interception afterwards
      .setLogRecordPredicate(record -> TransactionInterceptor.class.getName().equals(record.getLoggerName()))
      .assertLogRecords(records -> {
        final var interceptedMethods = records
            .stream()
            .map(TransactionInterceptorTest::formatLogRecord)
            .toList();
        Assertions.assertTrue(interceptedMethods.contains("Before singleWorkflowTask"),
            "Interceptor did not fire for a method having a single @WorkflowTask annotation: "
                + interceptedMethods);
        Assertions.assertTrue(interceptedMethods.contains("Before repeatedWorkflowTask"),
            "Interceptor did not fire for a method having two @WorkflowTask annotations: "
                + interceptedMethods);
        Assertions.assertTrue(interceptedMethods.contains("After repeatedWorkflowTask"),
            "Interceptor did not proceed for a method having two @WorkflowTask annotations: "
                + interceptedMethods);
        Assertions.assertFalse(interceptedMethods.contains("Before noWorkflowTask"),
            "Interceptor must not fire for a method not annotated by @WorkflowTask: "
                + interceptedMethods);
      });

  @Inject
  TaskService taskService;

  @Test
  public void testWorkflowTaskMethodsAreIntercepted() {

    // the actual assertions are done on the log records captured (see above)
    taskService.singleWorkflowTask();
    taskService.repeatedWorkflowTask();
    taskService.noWorkflowTask();

  }

  /**
   * Formats a log record regardless of whether the record's parameters were already
   * applied to the message by the logging bridge in use.
   *
   * @param record The log record
   * @return The formatted log message
   */
  private static String formatLogRecord(
      final LogRecord record) {

    var message = record.getMessage();
    for (final var parameter : record.getParameters() == null ? List.of() : List.of(record.getParameters())) {
      message = message.replaceFirst("\\{}", String.valueOf(parameter));
    }
    return message;

  }

}
