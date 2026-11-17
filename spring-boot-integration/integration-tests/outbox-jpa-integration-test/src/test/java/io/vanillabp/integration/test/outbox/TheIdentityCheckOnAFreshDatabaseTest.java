package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application starts for the first time on an empty database, and its adapter reports
 * the identity of its BPMS. A Camunda 8 adapter does that. The identity is compared with
 * the one in the delivery log while the application starts. So the table of the delivery
 * log has to exist before that question is asked, and VanillaBP creates it itself by
 * default.
 * <p>
 * The second start on the same database shows that the check still does its job: a BPMS
 * with another identity ends the start while the log holds records of its adapter.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class TheIdentityCheckOnAFreshDatabaseTest {

  /**
   * The own database of this test. Every other test of this module has created the tables
   * in its database already, so only a database of its own is empty at the first start.
   */
  private static final String DATASOURCE_URL = "jdbc:h2:mem:identity-check-on-a-fresh-database;DB_CLOSE_DELAY=-1";

  private static final String DELIVERY_TABLE = "VANILLABP_TASK_DELIVERY";

  private static final String ADAPTER_ID = "test";

  private static final String THE_FIRST_BPMS = "cluster-a";

  private static final String ANOTHER_BPMS = "cluster-b";

  @Test
  @DisplayName("The first start on an empty database writes down the identity of the BPMS")
  public void theFirstStartWritesDownTheIdentity() throws Exception {

    final List<String[]> processesOfTheIdentityRows;
    try (var context = run(THE_FIRST_BPMS)) {

      final var identities = identitiesWrittenDown(context);
      assertFalse(
          identities.isEmpty(),
          "the start has to write down the identity of the BPMS");
      identities.forEach(identity -> assertEquals(
          THE_FIRST_BPMS,
          identity[2],
          "the identity written down is the one the adapter reported"));
      processesOfTheIdentityRows = identities;

      // a record of the adapter, so a new BPMS could meet it
      final var deliveryLog = context.getBean(JdbcTaskDeliveryLog.class);
      context.getBean(TransactionTemplate.class).executeWithoutResult(status -> processesOfTheIdentityRows
          .forEach(process -> deliveryLog.record(TaskDelivery.workflowStart(
              ADAPTER_ID, process[0], process[1], "aggregate-of-the-first-bpms", "4711", Instant.now()))));

    }

    // the same BPMS again: the start goes on
    try (var context = run(THE_FIRST_BPMS)) {
      assertEquals(
          processesOfTheIdentityRows.size(),
          identitiesWrittenDown(context).size(),
          "a known identity is not written down a second time");
    }

    // another BPMS while the log holds records of its adapter: the start ends
    final var failure = assertThrows(
        RuntimeException.class,
        () -> run(ANOTHER_BPMS).close());
    assertTrue(
        messagesOf(failure).contains("is not the one this application met before"),
        "the start ends because of the other BPMS, and says so: "
            + messagesOf(failure));

  }

  /**
   * Runs the test application against the database of this test. Its adapter reports the
   * given identity of its BPMS.
   */
  private static ConfigurableApplicationContext run(
      final String identityOfTheBpms) {

    final var arguments = new ArrayList<String>();
    arguments.add("--spring.datasource.url="
        + DATASOURCE_URL);
    // nothing is dispatched in this test
    arguments.add("--vanillabp.outbox.poll-interval=PT1H");
    return new SpringApplicationBuilder(TestApplication.class)
        .initializers(context -> context
            .getBeanFactory()
            .registerSingleton("anIdentifiedBpms", new AnIdentifiedBpms(identityOfTheBpms)))
        .web(WebApplicationType.NONE)
        .run(arguments.toArray(String[]::new));

  }

  /**
   * The identity rows of the delivery log, each as workflow module id, BPMN process id and
   * identity.
   */
  private static List<String[]> identitiesWrittenDown(
      final ConfigurableApplicationContext context) throws SQLException {

    try (var connection = context.getBean(DataSource.class).getConnection(); var statement = connection
        .prepareStatement("SELECT WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, WORKFLOW_ID FROM "
            + DELIVERY_TABLE
            + " WHERE RECORD_KIND = 'BPMS_IDENTITY' AND ADAPTER_ID = ?")) {
      statement.setString(1, ADAPTER_ID);
      try (var resultSet = statement.executeQuery()) {
        final var identities = new ArrayList<String[]>();
        while (resultSet.next()) {
          identities.add(new String[]{
              resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)
          });
        }
        return identities;
      }
    }

  }

  /**
   * The messages of a failure and of all its causes, because Spring wraps the one of the
   * start.
   */
  private static String messagesOf(
      final Throwable failure) {

    final var messages = new StringBuilder();
    var cause = failure;
    while (cause != null) {
      messages.append(cause.getMessage()).append('\n');
      cause = cause.getCause() == cause
          ? null
          : cause.getCause();
    }
    return messages.toString();

  }

  /**
   * Makes the double report the identity of a BPMS, as a Camunda 8 adapter does. It knows
   * nothing about tasks, so the other sources answer those questions.
   */
  private record AnIdentifiedBpms(
                                  String identity) implements DummyTaskAwarenessSource {

    @Override
    public WorkflowAwareness awarenessOfTask(
        final String adapterId,
        final Object workflowAggregateId,
        final String taskId) {

      return null;

    }

    @Override
    public String identityOfTheBpms(
        final String adapterId) {

      return identity;

    }

  }

}
