package io.vanillabp.integration.adapter.migration.delivery;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * How a platform hands out a JDBC connection which takes part in the transaction
 * currently running - the one piece of {@link JdbcTaskDeliveryStore} which cannot be
 * platform-neutral: Spring Boot binds connections to the transaction through
 * {@code DataSourceUtils}, Quarkus enlists an Agroal connection in the active JTA
 * transaction when it is acquired.
 */
public interface JdbcConnectionAccess {

  /**
   * A connection enlisted in the transaction currently running on this thread.
   *
   * @return The connection
   * @throws SQLException If no connection can be acquired
   */
  Connection acquire() throws SQLException;

  /**
   * Returns the connection. The default closes it, which is right wherever the
   * connection pool hands out transaction-bound handles; a platform holding the
   * connection for the transaction's lifetime overrides this.
   *
   * @param connection The connection acquired before
   * @throws SQLException If the connection cannot be returned
   */
  default void release(
      final Connection connection) throws SQLException {

    connection.close();

  }

}
