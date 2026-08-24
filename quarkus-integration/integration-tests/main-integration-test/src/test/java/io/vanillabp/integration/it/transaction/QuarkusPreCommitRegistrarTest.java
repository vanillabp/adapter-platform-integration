package io.vanillabp.integration.it.transaction;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import jakarta.transaction.UserTransaction;

/**
 * A BPMS adapter hands its phase-one check to the platform and it runs right
 * before the transaction of the workflow aggregate commits. On Quarkus that is an interposed
 * JTA synchronization, so what this test pins is the ORDER (the check runs after everything
 * the caller did, not when it was handed over) and that a failing check takes the commit with
 * it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusPreCommitRegistrarTest {

  private static class OrderAggregate {
  }

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addAsResource("META-INF/workflow-module"));

  @Inject
  PreCommitRegistrar preCommitRegistrar;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("The check runs right before the commit, not when it was handed over")
  public void theCheckRunsBeforeTheCommit() throws Exception {

    final List<String> executions = new ArrayList<>();

    userTransaction.begin();
    preCommitRegistrar.beforeCommit(OrderAggregate.class, () -> executions.add("check"));
    executions.add("still inside the transaction");
    userTransaction.commit();

    Assertions.assertEquals(List.of("still inside the transaction", "check"), executions);

  }

  @Test
  @DisplayName("A failing check aborts the commit")
  public void aFailingCheckAbortsTheCommit() throws Exception {

    userTransaction.begin();
    preCommitRegistrar.beforeCommit(
        OrderAggregate.class,
        () -> {
          throw new IllegalStateException("the task is gone");
        });

    Assertions.assertThrows(RollbackException.class, () -> userTransaction.commit());

  }

  @Test
  @DisplayName("Without a transaction the check runs immediately")
  public void withoutATransactionTheCheckRunsImmediately() {

    final List<String> executions = new ArrayList<>();

    preCommitRegistrar.beforeCommit(OrderAggregate.class, () -> executions.add("check"));

    Assertions.assertEquals(List.of("check"), executions);

  }

}
