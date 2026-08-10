package io.vanillabp.integration.it.transaction;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.processservice.EventualConsistencyTransactionSupport;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import jakarta.transaction.UserTransaction;

/**
 * Tests the transaction-bound probes and after-commit actions of
 * {@link EventualConsistencyTransactionSupport}: probes have to run right before the
 * transaction is committed (a failing probe causes a rollback) and after-commit actions
 * have to run only if the transaction was committed successfully.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EventualConsistencyTransactionSupportTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addAsResource("META-INF/workflow-module"));

  @Inject
  EventualConsistencyTransactionSupport transactionSupport;

  @Inject
  UserTransaction userTransaction;

  @Test
  public void testProbesAndActionsRunOnCommit() throws Exception {

    final List<String> executions = new ArrayList<>();

    userTransaction.begin();
    transactionSupport.addProbeAndAction(
        () -> {
          executions.add("probe1");
          return "context1";
        },
        () -> "first probe",
        context -> executions.add("action1 with "
            + context),
        () -> "first action");
    transactionSupport.addProbeAndAction(
        () -> {
          executions.add("probe2");
          return "context2";
        },
        () -> "second probe",
        context -> executions.add("action2 with "
            + context),
        () -> "second action");

    Assertions.assertTrue(executions.isEmpty(),
        "Neither probes nor actions must run before committing the transaction");

    userTransaction.commit();

    Assertions.assertEquals(
        List.of("probe1", "probe2", "action1 with context1", "action2 with context2"),
        executions,
        "Probes have to run before after-commit actions and actions have to receive the probe's result");

  }

  @Test
  public void testProbesAndActionsDoNotRunOnRollback() throws Exception {

    final List<String> executions = new ArrayList<>();

    userTransaction.begin();
    transactionSupport.addProbeAndAction(
        () -> {
          executions.add("probe");
          return null;
        },
        () -> "probe",
        context -> executions.add("action"),
        () -> "action");

    userTransaction.rollback();

    Assertions.assertTrue(executions.isEmpty(),
        "Neither probes nor actions must run if the transaction is rolled back");

  }

  @Test
  public void testFailingProbeCausesRollback() throws Exception {

    final List<String> executions = new ArrayList<>();

    userTransaction.begin();
    transactionSupport.addProbeAndAction(
        () -> {
          throw new RuntimeException("BPMS not in expected state");
        },
        () -> "failing probe",
        context -> executions.add("action"),
        () -> "action");

    Assertions.assertThrows(RollbackException.class, userTransaction::commit,
        "A failing probe has to cause a rollback of the transaction");

    Assertions.assertTrue(executions.isEmpty(),
        "After-commit actions must not run if a probe failed");

  }

  @Test
  public void testAddingProbesOutsideTransactionsIsRejected() {

    Assertions.assertThrows(IllegalStateException.class,
        () -> transactionSupport.addProbeAndAction(
            () -> null,
            () -> "probe",
            context -> {
            },
            () -> "action"),
        "Adding probes and actions outside of a transaction has to be rejected");

  }

}
