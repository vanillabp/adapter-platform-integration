package io.vanillabp.integration.outbox.gruelbox;

import java.util.OptionalLong;

import javax.sql.DataSource;

import com.gruelbox.transactionoutbox.AlreadyScheduledException;
import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Spring Boot applications using
 * JPA: delegates to a <a href="https://github.com/gruelbox/transaction-outbox">gruelbox
 * transaction-outbox</a> configured with Spring's transaction manager, so the outbox
 * entry is enlisted in the currently running local (JDBC) transaction.
 * <p>
 * The idempotency contract of {@link PhaseTwoOutbox} maps onto gruelbox's
 * <code>uniqueRequestId</code> mechanism: the {@link PhaseTwoCall#idempotencyKey()} is
 * used as unique request ID, enforced by a unique constraint of gruelbox's outbox
 * table. A duplicate schedule raises {@link AlreadyScheduledException} which is turned
 * into the contract's no-op (<code>false</code>). Successfully dispatched entries with
 * a unique request ID are retained by gruelbox until the configured retention
 * threshold passes (the contract's "DONE instead of delete").
 */
@Slf4j
public class GruelboxPhaseTwoOutbox implements PhaseTwoOutbox {

  private final TransactionOutbox transactionOutbox;

  /**
   * Where gruelbox' table lives, needed to count the entries waiting for their
   * dispatch. <code>null</code> where the caller did not supply one - the pending
   * meter is then absent rather than wrong.
   */
  private final DataSource dataSource;

  /**
   * The table gruelbox stores its entries in.
   */
  private final String tableName;

  /**
   * Creates an outbox which cannot count its pending entries - kept for tests.
   *
   * @param transactionOutbox The gruelbox transaction outbox
   */
  public GruelboxPhaseTwoOutbox(
      final TransactionOutbox transactionOutbox) {

    this(transactionOutbox, null, null);

  }

  /**
   * @param transactionOutbox The gruelbox transaction outbox
   * @param dataSource Where gruelbox' table lives
   * @param tableName The table gruelbox stores its entries in
   */
  public GruelboxPhaseTwoOutbox(
      final TransactionOutbox transactionOutbox,
      final DataSource dataSource,
      final String tableName) {

    this.transactionOutbox = transactionOutbox;
    this.dataSource = dataSource;
    this.tableName = tableName;

  }

  /**
   * Counts the entries gruelbox has not processed yet. gruelbox has no API for it, so
   * the count reads its table directly - along the index it creates itself
   * (<code>IX_TXNO_OUTBOX_1</code> over <code>processed, blocked,
   * nextAttemptTime</code>), which is why one column is enough and no dialect-specific
   * literal is needed: the driver knows how to write a boolean into whatever type the
   * column has on this database.
   */
  @Override
  public OptionalLong pendingCalls() {

    if ((dataSource == null) || (tableName == null)) {
      return OptionalLong.empty();
    }
    final var countPending = "SELECT COUNT(*) FROM %s WHERE processed = ?".formatted(tableName);
    try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(countPending)) {
      statement.setBoolean(1, false);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? OptionalLong.of(resultSet.getLong(1))
            : OptionalLong.empty();
      }
    } catch (final java.sql.SQLException e) {
      // a metric must never be the reason an application fails - the gauge reports
      // nothing for this collection and the next one tries again
      log.debug("Could not count the pending entries of gruelbox' outbox table '{}'", tableName, e);
      return OptionalLong.empty();
    }

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    try {
      transactionOutbox
          .with()
          .uniqueRequestId(call
              .idempotencyKey()
              .orElse(null))
          .schedule(GruelboxPhaseTwoDispatch.class)
          .dispatch(
              call.operation(),
              call.workflowModuleId(),
              call.bpmnProcessId(),
              call.workflowAggregateId(),
              call.adapterId(),
              PhaseTwoCall.serializeArgs(call.args()));
      return true;
    } catch (AlreadyScheduledException e) {
      log.debug(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "was already scheduled - skipping",
          call.operation(),
          call.bpmnProcessId(),
          call.workflowModuleId(),
          call.workflowAggregateId());
      return false;
    }

  }

}
