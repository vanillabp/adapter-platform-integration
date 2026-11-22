package io.vanillabp.loadharness;

/**
 * Reads the connection pool of the application. Both platforms publish these numbers as
 * meters already: <code>hikaricp.connections.active</code> and
 * <code>hikaricp.connections.pending</code> on Spring Boot, <code>agroal.active.count</code>
 * and <code>agroal.awaiting.count</code> on Quarkus. The harness reads the pool directly
 * instead, so the application needs no metrics module of its platform for it.
 */
public interface ConnectionPoolProbe {

  /**
   * A probe for an application whose pool cannot be read. It reports zero for both.
   */
  ConnectionPoolProbe NONE = new ConnectionPoolProbe() {

    @Override
    public int active() {

      return 0;

    }

    @Override
    public int pending() {

      return 0;

    }

  };

  /**
   * The connections in use right now.
   *
   * @return The number of connections in use
   */
  int active();

  /**
   * The threads which wait for a connection right now.
   *
   * @return The number of waiting threads
   */
  int pending();

}
