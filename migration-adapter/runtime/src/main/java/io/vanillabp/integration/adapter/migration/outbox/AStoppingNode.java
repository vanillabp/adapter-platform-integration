package io.vanillabp.integration.adapter.migration.outbox;

/**
 * Whether what a store just threw means that the node is being stopped, and not that
 * something is wrong.
 * <p>
 * A dispatch may be interrupted while it writes down how it ended. Then the database driver
 * throws, and the two ways to read that exception could not be further apart. One is an
 * outbox which cannot reach its database, and somebody has to look at it. The other is a
 * pod which is being replaced. There the entry stays open, keeps its lease, and is
 * dispatched again by whoever picks it up next. That is the ordinary case, and nothing to
 * look for at three in the morning.
 * <p>
 * It is asked here and not in each store, because all four stores ask it. The distinction
 * belongs to the outbox, not to one database.
 *
 * <h2>How it is recognized</h2>
 *
 * The interrupt flag of the thread is the first answer, and the most reliable one. A driver
 * which turns an interruption into an exception of its own sets the flag again while it
 * does so. The chain of causes is the second answer, for a driver which does not. The
 * wrapper of the MongoDB driver is matched by NAME and not by type. The core has no MongoDB
 * dependency, and must not get one for a question which a name answers.
 */
public final class AStoppingNode {

  /**
   * The wrapper of the MongoDB driver around an interruption
   * (<code>com.mongodb.MongoInterruptedException</code>). It is matched by name. This module
   * has no MongoDB dependency, and the two stores which have one are not the only callers.
   */
  private static final String MONGO_INTERRUPTION = "MongoInterruptedException";

  private AStoppingNode() {

  }

  /**
   * Whether the given failure is this node being stopped.
   *
   * @param failure What the store threw
   * @return Whether the work was interrupted rather than failed
   */
  public static boolean isTheReasonFor(
      final Throwable failure) {

    if (Thread.currentThread().isInterrupted()) {
      return true;
    }
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if ((cause instanceof InterruptedException) || (cause instanceof java.io.InterruptedIOException) || MONGO_INTERRUPTION
          .equals(cause.getClass().getSimpleName())) {
        return true;
      }
      if (cause.getCause() == cause) {
        // a driver which wrapped an exception in itself would keep this loop going forever
        return false;
      }
    }
    return false;

  }

}
