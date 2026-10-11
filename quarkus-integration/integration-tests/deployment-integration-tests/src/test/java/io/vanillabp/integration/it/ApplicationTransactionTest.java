package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.deployment.NestedTransactionalBean;
import io.vanillabp.integration.test.deployment.TransactionAggregate;
import io.vanillabp.integration.test.deployment.TransactionAggregatePersistence;
import io.vanillabp.integration.test.deployment.TransactionProcessWiringSource;
import io.vanillabp.integration.test.deployment.TransactionWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of the safeguards of the transaction contract on Quarkus. A transaction
 * annotation of the application in the call chain of a <code>&#64;WorkflowTask</code>
 * handler marks VanillaBP's JTA transaction rollback-only. After that, neither the
 * aggregate changes nor the state of the BPMS can be committed. Narayana refuses the commit
 * of a transaction in state {@code STATUS_MARKED_ROLLBACK}. Without the check, the
 * developer would only get Arjuna's wording, one layer away from the cause.
 * <p>
 * The deployment module's {@code TransactionAnnotationStartupTest} asserts the startup
 * check for an annotation ON the handler. The core's unit tests cover the matrix of
 * annotations.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ApplicationTransactionTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TransactionProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("transaction/application.yaml", "application.yaml")
          .addClass(TransactionAggregate.class)
          .addClass(TransactionAggregatePersistence.class)
          .addClass(NestedTransactionalBean.class)
          .addClass(TransactionWorkflowService.class)
          .addClass(TransactionProcessWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/TransactionProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  TransactionAggregatePersistence persistence;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .filter(service -> "demo1".equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  private TaskInvocationContext context(
      final String taskDefinition,
      final String aggregateId,
      final boolean runInCurrentTransaction) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public boolean runInCurrentTransaction() {
        return runInCurrentTransaction;
      }

    };

  }

  private void assertGuidingMessage(
      final IllegalStateException failure,
      final String taskDefinition) {

    assertTrue(failure.getMessage().contains("marked rollback-only"), failure.getMessage());
    assertTrue(failure.getMessage().contains(taskDefinition), failure.getMessage());
    assertTrue(failure.getMessage().contains(PROCESS), failure.getMessage());
    assertTrue(failure.getMessage().contains(MODULE), failure.getMessage());
    // the rollback rule as it is written on THIS platform. Quarkus honors the JTA
    // annotation. It honors Spring's only with the extension quarkus-spring-tx, and this
    // application does not have it. So Spring's attribute must not be offered here
    assertTrue(failure.getMessage().contains("dontRollbackOn = TaskException.class"), failure.getMessage());
    assertFalse(failure.getMessage().contains("noRollbackFor"), failure.getMessage());

  }

  @Test
  @DisplayName("A transaction annotation in the call chain fails the task instead of losing the changes")
  public void rollbackOnlyTransactionsAreReported() {

    final var dummyAdapter = dummyAdapter();

    // (a) the shape of a remote BPMS: VanillaBP opens its own transaction. The nested
    // bean joins it and takes it down with the TaskException
    persistence.seed("5001");
    final var ownTransaction = assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("nestedTaskException", "5001", false)));
    assertGuidingMessage(ownTransaction, "nestedTaskException");

    // (b) the shape of an embedded engine: VanillaBP participates in the caller's
    // transaction
    persistence.seed("5002");
    final var joinedTransaction = assertThrows(
        IllegalStateException.class,
        () -> QuarkusTransaction
            .requiringNew()
            .call(() -> dummyAdapter.invokeTask(MODULE, PROCESS, context("nestedTaskException", "5002", true))));
    assertGuidingMessage(joinedTransaction, "nestedTaskException");

    // (c) no TaskException involved at all. The handler swallowed the exception of the
    // nested call and returned normally. That would report the task as completed,
    // although nothing can be stored
    persistence.seed("5003");
    final var swallowed = assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("swallowedNestedFailure", "5003", false)));
    assertGuidingMessage(swallowed, "swallowedNestedFailure");

    // (d) the annotation which a version-1 application carries. The startup check
    // accepts it (this application booted with it), and it works at runtime
    persistence.seed("5004");
    final var accepted = dummyAdapter.invokeTask(MODULE, PROCESS, context("acceptedAnnotation", "5004", false));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, accepted.kind());
    assertEquals("accepted", persistence.stored("5004").getStatus());

  }

}
