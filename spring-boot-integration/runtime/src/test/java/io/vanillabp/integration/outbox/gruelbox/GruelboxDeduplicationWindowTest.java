package io.vanillabp.integration.outbox.gruelbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * gruelbox owns the table its unique request IDs live in, and its unique constraint
 * spans the entries it retains after a dispatch as well. Since a key deduplicates what
 * is PLANNED and not what happened (decision 22 in the repository's DECISIONS.md), this
 * store has to release a retained entry when a new operation of the same key arrives -
 * which is what is pinned here, together with the length gruelbox accepts at all.
 * <p>
 * The dispatching half is covered end-to-end by the outbox integration test; what needs
 * this level is the table itself, because "the entry gruelbox kept was released" cannot
 * be seen from the outside.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GruelboxDeduplicationWindowTest {

  private static final String TABLE = GruelboxPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_TABLE_NAME;

  private static SingleConnectionDataSource h2() {

    final var dataSource = new SingleConnectionDataSource(
        "jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID()), "sa", "", true);
    dataSource.setDriverClassName("org.h2.Driver");
    return dataSource;

  }

  /**
   * The context gruelbox instantiates its dispatch class through - it has to outlive
   * every schedule of a test, which is why it is closed afterwards and not right away.
   */
  private AnnotationConfigApplicationContext context;

  @AfterEach
  public void closeTheContext() {

    if (context != null) {
      context.close();
      context = null;
    }

  }

  /**
   * A store on a fresh in-memory database, with gruelbox' own migration applied.
   */
  private GruelboxPhaseTwoOutbox outbox(
      final DataSource dataSource) {

    context = new AnnotationConfigApplicationContext();
    // gruelbox resolves the scheduled invocation through the context; nothing is
    // dispatched here, so a bean doing nothing is enough
    context
        .registerBean(
            GruelboxPhaseTwoDispatch.class,
            () -> (
                operation,
                workflowModuleId,
                bpmnProcessId,
                workflowAggregateId,
                adapterId,
                serializedArgs) -> {
              // no dispatch in this test: what is asserted is the scheduling window
            });
    context.refresh();
    final var transactionOutbox = new GruelboxPhaseTwoOutboxAutoConfiguration()
        .vanillaBpTransactionOutbox(
            context,
            Map.of("transactionManager", new DataSourceTransactionManager(dataSource)),
            dataSource,
            new VanillaBpConfigurationProperties());
    return new GruelboxPhaseTwoOutbox(transactionOutbox, dataSource, TABLE);

  }

  private static PhaseTwoCall correlation(
      final String aggregateId,
      final String correlationId) {

    return PhaseTwoCall
        .of(
            PhaseTwoOperation.CORRELATE_MESSAGE, "test-module", "TestProcess", aggregateId, null, Map
                .of(
                    PhaseTwoCall.ARG_MESSAGE_NAME, "OfferRequested",
                    PhaseTwoCall.ARG_CORRELATION_ID, correlationId));

  }

  /**
   * Schedules the call in a transaction of its own - the store has to be called within
   * one, and the answer is what the contract is about.
   */
  private static boolean scheduled(
      final TransactionTemplate transactions,
      final GruelboxPhaseTwoOutbox outbox,
      final PhaseTwoCall call) {

    return Boolean.TRUE.equals(transactions.execute(status -> outbox.schedule(call)));

  }

  private static int update(
      final DataSource dataSource,
      final String sql) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      return statement.executeUpdate(sql);
    }

  }

  private static long count(
      final DataSource dataSource,
      final String where) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      try (var resultSet = statement.executeQuery("SELECT COUNT(*) FROM %s WHERE %s".formatted(TABLE, where))) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }

  }

  @Test
  @DisplayName("A retained entry of a dispatched operation is released for the next round")
  public void aDispatchedEntryIsReleased() throws Exception {

    final var dataSource = h2();
    final var testee = outbox(dataSource);
    final var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    assertTrue(scheduled(transactions, testee, correlation("4711", "partner-42")));

    // the same operation while the first one waits: gruelbox' unique request ID is the
    // window, and it is closed
    assertFalse(scheduled(transactions, testee, correlation("4711", "partner-42")));

    // gruelbox dispatched the entry and keeps it until its retention passes - this is
    // what the store has to see through
    assertEquals(1, update(dataSource, "UPDATE %s SET processed = TRUE".formatted(TABLE)));

    // the second round of a loop asking partner 42 again: planned, and the retained
    // entry is gone since gruelbox' table has no column to move its key into
    assertTrue(scheduled(transactions, testee, correlation("4711", "partner-42")));
    assertEquals(0, count(dataSource, "processed = TRUE"));
    assertEquals(1, count(dataSource, "processed = FALSE"));

  }

  @Test
  @DisplayName("A key too long for gruelbox is hashed instead of ending the caller's transaction")
  public void anOversizedKeyIsScheduled() throws Exception {

    final var dataSource = h2();
    final var testee = outbox(dataSource);
    final var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    // gruelbox refuses a unique request ID longer than 250 characters before any
    // database sees it, so an aggregate ID a domain model legitimately uses - a
    // composite business key, a URN - used to fail the application's own transaction
    final var aggregateId = "urn:offer:"
        + "x".repeat(400);

    assertTrue(scheduled(transactions, testee, correlation(aggregateId, "partner-42")));
    assertEquals(1, count(dataSource, "uniqueRequestId LIKE 'sha256:%'"));

    // and the hash still deduplicates while the entry waits
    assertFalse(scheduled(transactions, testee, correlation(aggregateId, "partner-42")));

  }

  @Test
  @DisplayName("An operation without a key is never discarded")
  public void aKeylessOperationIsAlwaysScheduled() {

    final var dataSource = h2();
    final var testee = outbox(dataSource);
    final var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    final var signal = PhaseTwoCall
        .of(
            PhaseTwoOperation.SEND_SIGNAL, "test-module", "TestProcess", null, "test-adapter", Map
                .of(PhaseTwoCall.ARG_SIGNAL_NAME, "OfferWithdrawn"));

    assertTrue(scheduled(transactions, testee, signal));
    assertTrue(scheduled(transactions, testee, signal));

  }

}
