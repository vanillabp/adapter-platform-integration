package io.vanillabp.integration.adapter.migration.transaction;

import java.util.Set;

/**
 * Tells whether a failed write took the whole transaction with it. Then nothing which that
 * transaction wrote, before or after the failure, can commit any more.
 * <p>
 * The core catches the failure of a write which is only a hint, and goes on without it. That
 * is right where the database goes on as well. It is wrong where the database has already
 * ended the transaction. Then going on means more work which cannot commit, and a message
 * which says that only the hint was lost. So such a failure is passed on instead.
 * <p>
 * Two databases end the transaction like that:
 * <ul>
 * <li>MongoDB ends it at a write conflict, error code 112. That is a second writer of the same
 * document in another transaction. Error code 251 ("NoSuchTransaction") comes later, from
 * whatever still used the transaction which the server already ended.</li>
 * <li>PostgreSQL ends it at the first statement which fails. Every later statement is refused
 * with SQL state <code>25P02</code> ("in failed SQL transaction"). A deadlock
 * (<code>40P01</code>) and a failed serialization (<code>40001</code>) end the transaction on
 * every database.</li>
 * </ul>
 * The exceptions are matched by name and by code, not by type. The core is plain Java and does
 * not depend on the MongoDB driver.
 */
public final class TransactionLoss {

  /**
   * The error codes by which MongoDB says that a transaction is gone: a write conflict, and
   * "no such transaction".
   */
  private static final Set<Integer> MONGODB_CODES = Set.of(112, 251);

  /**
   * The label which MongoDB puts on every error after which the whole transaction has to be
   * run again.
   */
  private static final String MONGODB_LABEL = "TransientTransactionError";

  private static final String MONGODB_EXCEPTION = "com.mongodb.MongoException";

  /**
   * The SQL states after which the transaction cannot commit any more.
   */
  private static final Set<String> SQL_STATES = Set.of("25P02", "40001", "40P01");

  private TransactionLoss() {
  }

  /**
   * Whether the failure, or one of its causes, says that the database ended the transaction.
   *
   * @param failure The exception a write produced
   * @return Whether the transaction in which the write ran is gone
   */
  public static boolean endedTheTransaction(
      final Throwable failure) {

    var candidate = failure;
    while (candidate != null) {
      if (isAMongoDbTransactionError(candidate)) {
        return true;
      }
      if ((candidate instanceof final java.sql.SQLException sqlException) && (sqlException
          .getSQLState() != null) && SQL_STATES.contains(sqlException.getSQLState())) {
        return true;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return false;

  }

  /**
   * Whether the failure is a <code>com.mongodb.MongoException</code>, or a subclass of it,
   * which names a lost transaction by its code or by its label. Both are read by reflection.
   */
  private static boolean isAMongoDbTransactionError(
      final Throwable failure) {

    Class<?> type = failure.getClass();
    while ((type != null) && !MONGODB_EXCEPTION.equals(type.getName())) {
      type = type.getSuperclass();
    }
    if (type == null) {
      return false;
    }
    try {
      if (MONGODB_CODES.contains(type.getMethod("getCode").invoke(failure))) {
        return true;
      }
      return Boolean.TRUE.equals(type.getMethod("hasErrorLabel", String.class).invoke(failure, MONGODB_LABEL));
    } catch (final ReflectiveOperationException | RuntimeException noCode) {
      return false;
    }

  }

}
