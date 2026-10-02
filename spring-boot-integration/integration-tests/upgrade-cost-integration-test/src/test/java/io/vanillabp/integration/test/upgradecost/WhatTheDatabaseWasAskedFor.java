package io.vanillabp.integration.test.upgradecost;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/**
 * The meter of this scenario: a data source which hands out connections that count what
 * they are asked for, per thread.
 * <p>
 * Per thread, because the question of this measurement is who pays. A start writes its
 * outbox entry in the thread of the caller and the entry leaves in a thread of the
 * dispatch, and a count over the whole application would add the two together and lose
 * exactly the part an upgrader wants to know.
 * <p>
 * Commits are counted rather than transactions begun: a transaction which commits is one
 * the database did work for, and a read-only transaction Spring never commits is not a
 * cost this measurement talks about. Statements are counted where they are created, which
 * is one count per statement a connection is asked to prepare.
 */
public class WhatTheDatabaseWasAskedFor implements DataSource {

  /**
   * The methods of a connection which create a statement. Counted together, because a
   * driver is free to answer any of them for the same SQL.
   */
  private static final Set<String> CREATES_A_STATEMENT = Set
      .of("prepareStatement", "createStatement", "prepareCall");

  private final DataSource delegate;

  private final Map<String, AtomicInteger> commits = new ConcurrentHashMap<>();

  private final Map<String, AtomicInteger> statements = new ConcurrentHashMap<>();

  public WhatTheDatabaseWasAskedFor(
      final DataSource delegate) {

    this.delegate = delegate;

  }

  /**
   * Forgets everything counted so far. A measurement starts with this, so that the boot of
   * the application and whatever a test before it did are not part of the count.
   */
  public void startCounting() {

    commits.clear();
    statements.clear();

  }

  /**
   * How often the given thread committed since {@link #startCounting()}.
   *
   * @param threadName The exact name of the thread asked about
   * @return Its commits
   */
  public int commitsOf(
      final String threadName) {

    return count(commits, threadName);

  }

  /**
   * How many statements the given thread had prepared since {@link #startCounting()}.
   *
   * @param threadName The exact name of the thread asked about
   * @return Its statements
   */
  public int statementsOf(
      final String threadName) {

    return count(statements, threadName);

  }

  /**
   * The names of the threads which committed something since {@link #startCounting()}.
   *
   * @return Their names
   */
  public Set<String> threadsWhichCommitted() {

    return Set.copyOf(commits.keySet());

  }

  private static int count(
      final Map<String, AtomicInteger> of,
      final String threadName) {

    final var counter = of.get(threadName);
    return counter == null
        ? 0
        : counter.get();

  }

  private static void countOne(
      final Map<String, AtomicInteger> in) {

    in
        .computeIfAbsent(Thread.currentThread().getName(), thread -> new AtomicInteger())
        .incrementAndGet();

  }

  private Connection counting(
      final Connection connection) {

    return (Connection) Proxy
        .newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{
                Connection.class
            },
            (
                proxy,
                method,
                args) -> {
              if ("commit".equals(method.getName())) {
                countOne(commits);
              } else if (CREATES_A_STATEMENT.contains(method.getName())) {
                countOne(statements);
              }
              try {
                return method.invoke(connection, args);
              } catch (final java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
              }
            });

  }

  @Override
  public Connection getConnection() throws SQLException {

    return counting(delegate.getConnection());

  }

  @Override
  public Connection getConnection(
      final String username,
      final String password) throws SQLException {

    return counting(delegate.getConnection(username, password));

  }

  @Override
  public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {

    return delegate.getParentLogger();

  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {

    return delegate.getLogWriter();

  }

  @Override
  public void setLogWriter(
      final PrintWriter out) throws SQLException {

    delegate.setLogWriter(out);

  }

  @Override
  public void setLoginTimeout(
      final int seconds) throws SQLException {

    delegate.setLoginTimeout(seconds);

  }

  @Override
  public int getLoginTimeout() throws SQLException {

    return delegate.getLoginTimeout();

  }

  @Override
  public <T> T unwrap(
      final Class<T> iface) throws SQLException {

    return iface.isInstance(this)
        ? iface.cast(this)
        : delegate.unwrap(iface);

  }

  @Override
  public boolean isWrapperFor(
      final Class<?> iface) throws SQLException {

    return iface.isInstance(this) || delegate.isWrapperFor(iface);

  }

}
