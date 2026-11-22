package io.vanillabp.loadharness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A JDBC driver which sits in front of the real one and tells {@link StatementCounter} about
 * every statement which runs. The application names it instead of its real driver, and puts
 * <code>counting:</code> after the <code>jdbc:</code> of its URL:
 *
 * <pre>
 * jdbc:counting:postgresql://localhost:5432/load
 * </pre>
 * <p>
 * It works the same under every connection pool, so both platforms count the same way.
 * Everything else is handed to the real driver unchanged, the metadata included. So
 * VanillaBP sees the database it runs on.
 */
public class CountingDriver implements Driver {

  /**
   * What a URL starts with which this driver serves.
   */
  public static final String PREFIX = "jdbc:counting:";

  private static final Set<String> EXECUTING = Set
      .of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch");

  static {
    try {
      DriverManager.registerDriver(new CountingDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * A driver. The class registers one instance itself when it is loaded.
   */
  public CountingDriver() {
  }

  @Override
  public Connection connect(
      final String url,
      final Properties info) throws SQLException {

    if (!acceptsURL(url)) {
      return null;
    }
    final var real = DriverManager.getConnection(realUrl(url), info);
    return countingConnection(real);

  }

  /**
   * The URL of the real driver.
   *
   * @param url The URL with <code>counting:</code>
   * @return The URL without it
   */
  static String realUrl(
      final String url) {

    return "jdbc:"
        + url.substring(PREFIX.length());

  }

  @Override
  public boolean acceptsURL(
      final String url) {

    return (url != null) && url.startsWith(PREFIX);

  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(
      final String url,
      final Properties info) throws SQLException {

    return DriverManager.getDriver(realUrl(url)).getPropertyInfo(realUrl(url), info);

  }

  @Override
  public int getMajorVersion() {

    return 1;

  }

  @Override
  public int getMinorVersion() {

    return 0;

  }

  @Override
  public boolean jdbcCompliant() {

    return false;

  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {

    throw new SQLFeatureNotSupportedException("The counting driver has no logger of its own");

  }

  private static Connection countingConnection(
      final Connection real) {

    return (Connection) Proxy
        .newProxyInstance(
            CountingDriver.class.getClassLoader(),
            new Class<?>[]{
                Connection.class
            },
            (
                proxy,
                method,
                args) -> {
              final var answer = invoke(real, method, args);
              if (answer instanceof CallableStatement statement) {
                return countingStatement(statement, CallableStatement.class);
              }
              if (answer instanceof PreparedStatement statement) {
                return countingStatement(statement, PreparedStatement.class);
              }
              if (answer instanceof Statement statement) {
                return countingStatement(statement, Statement.class);
              }
              return answer;
            });

  }

  private static Object countingStatement(
      final Statement real,
      final Class<? extends Statement> type) {

    return Proxy
        .newProxyInstance(
            CountingDriver.class.getClassLoader(),
            new Class<?>[]{
                type
            },
            (
                proxy,
                method,
                args) -> {
              if (EXECUTING.contains(method.getName())) {
                StatementCounter.ran();
              }
              return invoke(real, method, args);
            });

  }

  private static Object invoke(
      final Object target,
      final java.lang.reflect.Method method,
      final Object[] args) throws Throwable {

    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      // the caller expects the SQLException of the driver, not the wrapper of reflection
      throw e.getCause();
    }

  }

}
