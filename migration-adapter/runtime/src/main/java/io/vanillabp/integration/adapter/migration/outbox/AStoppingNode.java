package io.vanillabp.integration.adapter.migration.outbox;

/**
 * Whether what a store just threw is the node being stopped rather than something wrong.
 * <p>
 * A dispatch which is interrupted while it writes down how it ended throws out of the
 * database driver, and the two readings of that exception could not be further apart. One
 * is an outbox which cannot reach its database, which somebody has to look at. The other
 * is a pod being replaced, where the entry stays open, keeps its lease, and is dispatched
 * again by whoever picks it up next - the ordinary case, and nothing to look for at
 * three in the morning.
 * <p>
 * The reason it is asked here and not in each store is that all four ask it: the
 * distinction belongs to the outbox, not to one database.
 *
 * <h2>How it is recognized</h2>
 *
 * The interrupt flag of the thread is the first answer, and the most reliable: a driver
 * which turns an interruption into an exception of its own restores the flag while it does
 * so. The chain of causes is the second, for a driver which does not. The MongoDB driver's
 * own wrapper is matched by NAME rather than by type, because the core carries no MongoDB
 * dependency and must not gain one for a question a name answers.
 */
public final class AStoppingNode {

  /**
   * The MongoDB driver's wrapper around an interruption
   * (<code>com.mongodb.MongoInterruptedException</code>). Matched by name: this module has
   * no MongoDB dependency, and the two stores which do are not the only callers.
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
        // a driver which wrapped an exception in itself would keep this walking
        return false;
      }
    }
    return false;

  }

}
